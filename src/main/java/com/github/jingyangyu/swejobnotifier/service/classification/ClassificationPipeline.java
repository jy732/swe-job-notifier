package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.service.PipelineMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Three-stage classification pipeline that assigns a level (L3/L4/L3_OR_L4/OTHER) to each job.
 *
 * <p>The stages run in order, each consuming only the jobs that the previous stage couldn't
 * classify:
 *
 * <ol>
 *   <li><b>Stage 1 — Title rules:</b> Regex patterns ({@link FilterKeywords#L4_PATTERN}, {@link
 *       FilterKeywords#L3_PATTERN}) and L3 title keywords. Zero-cost, high-confidence.
 *   <li><b>Stage 2 — Description signals:</b> YOE patterns ("3+ years" → L4, "0-1 years" → L3) and
 *       L3 keywords in the JD. Still local, no API call. This stage was previously skipped — all
 *       ambiguous jobs went straight to Gemini.
 *   <li><b>Stage 3 — Gemini LLM:</b> 4-way classification for the remaining ambiguous jobs.
 *       Batched, retried, most expensive.
 * </ol>
 *
 * <p>The caller receives a {@link Result} containing the merged level map, the list of Gemini
 * failures, and per-stage counts for observability.
 */
@Slf4j
@Component
public class ClassificationPipeline {

    private final JobTitleFilter titleFilter;
    private final JobClassifier classifier;
    private final PipelineMetrics metrics;

    public ClassificationPipeline(
            JobTitleFilter titleFilter, JobClassifier classifier, PipelineMetrics metrics) {
        this.titleFilter = titleFilter;
        this.classifier = classifier;
        this.metrics = metrics;
    }

    /**
     * Runs all three classification stages on the given unseen jobs.
     *
     * @param unseen jobs that passed pre-filtering and dedup
     * @return merged classification result with per-stage counts
     */
    public Result classify(List<JobPosting> unseen) {
        Map<JobPosting, String> levelMap = new HashMap<>();
        List<JobPosting> remaining = new ArrayList<>(unseen);

        // Stage 1: title-based rules
        List<JobPosting> afterStage1 = new ArrayList<>();
        for (JobPosting job : remaining) {
            String level = titleFilter.autoClassifyLevel(job);
            if (level != null) {
                levelMap.put(job, level);
            } else {
                afterStage1.add(job);
            }
        }
        int stage1Count = levelMap.size();

        // Stage 2: description signal-based rules
        List<JobPosting> needsGemini = new ArrayList<>();
        for (JobPosting job : afterStage1) {
            String level = SignalExtractor.inferLevelFromDescription(job);
            if (level != null) {
                levelMap.put(job, level);
            } else {
                needsGemini.add(job);
            }
        }
        int stage2Count = levelMap.size() - stage1Count;

        metrics.recordClassifyStage1(stage1Count);
        metrics.recordClassifyStage2(stage2Count);
        metrics.recordClassifyStage3(needsGemini.size());

        log.info(
                "Classification pipeline: {} total → {} stage1 (title) + {} stage2 (description)"
                        + " + {} stage3 (Gemini)",
                unseen.size(),
                stage1Count,
                stage2Count,
                needsGemini.size());

        // Stage 3: Gemini LLM
        List<JobPosting> geminiFailed = List.of();
        if (!needsGemini.isEmpty()) {
            ClassificationResult result = classifier.classify(needsGemini);
            levelMap.putAll(result.getLevelMap());
            geminiFailed = result.getFailed();
        }

        return new Result(levelMap, geminiFailed, stage1Count, stage2Count, needsGemini.size());
    }

    /**
     * Result of the three-stage classification pipeline.
     *
     * @param levelMap merged level assignments from all stages
     * @param geminiFailed jobs where Gemini API calls failed (will retry next poll)
     * @param stage1Count jobs classified by title rules
     * @param stage2Count jobs classified by description signals
     * @param stage3Count jobs sent to Gemini
     */
    public record Result(
            Map<JobPosting, String> levelMap,
            List<JobPosting> geminiFailed,
            int stage1Count,
            int stage2Count,
            int stage3Count) {}
}
