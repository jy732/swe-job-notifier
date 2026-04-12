package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.service.PipelineMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates job classification via Gemini with batching, rate limiting, and retry.
 *
 * <p>This class handles the "how" of classification (batching, pacing, error handling) while {@link
 * GeminiClient} handles the "what" (API calls, prompt building, response parsing).
 *
 * <p>Uses a single 4-way level classification (L3/L4/L3_OR_L4/OTHER) per batch instead of separate
 * Y/N and level calls. The caller derives {@code midLevel} from the level.
 *
 * <p>Design decisions:
 *
 * <ul>
 *   <li>Jobs are sent in batches of {@value #BATCH_SIZE} to stay within token limits.
 *   <li>Each batch is retried up to 3 times with exponential backoff (5s -> 10s -> 20s).
 *   <li>When no API key is configured, all pre-filtered jobs are returned as L4 — this lets the app
 *       run in dev mode without Gemini, relying solely on local title filters.
 * </ul>
 */
@Slf4j
@Service
public class JobClassifier {

    private static final int BATCH_SIZE = 50;

    private final GeminiClient geminiClient;
    private final PipelineMetrics metrics;
    private final RetryTemplate retryTemplate;

    public JobClassifier(GeminiClient geminiClient, PipelineMetrics metrics) {
        this.geminiClient = geminiClient;
        this.metrics = metrics;
        this.retryTemplate =
                RetryTemplate.builder().maxAttempts(3).exponentialBackoff(5000, 2, 20000).build();
    }

    /**
     * Classifies a list of job postings via Gemini's 4-way level classification in batches.
     *
     * <p>Returns a {@link ClassificationResult} containing the level map and failed jobs. The
     * caller derives {@code midLevel} from the level (L4 or L3_OR_L4 → mid-level).
     *
     * <p>If no API key is configured, all jobs are mapped to "L4" — this is intentional so the app
     * can run in dev mode without Gemini, relying solely on local title filters.
     */
    public ClassificationResult classify(List<JobPosting> jobs) {
        if (jobs.isEmpty()) {
            return new ClassificationResult(Collections.emptyMap(), Collections.emptyList());
        }
        if (!geminiClient.isConfigured()) {
            log.warn("Gemini API key not configured — returning all {} jobs as L4", jobs.size());
            Map<JobPosting, String> allL4 = new HashMap<>();
            jobs.forEach(j -> allL4.put(j, "L4"));
            return new ClassificationResult(allL4, Collections.emptyList());
        }

        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info("Gemini classification: {} job(s) in {} batch(es)", jobs.size(), totalBatches);
        Map<JobPosting, String> levelMap = new HashMap<>();
        List<JobPosting> failed = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            Map<JobPosting, String> batchResult = classifyBatchWithRetry(batch);
            if (batchResult == null) {
                failed.addAll(batch);
                metrics.recordGeminiFail();
                log.warn(
                        "Gemini batch {}/{} failed — {} job(s) will retry next poll",
                        batchNum,
                        totalBatches,
                        batch.size());
            } else {
                levelMap.putAll(batchResult);
                metrics.recordGeminiSuccess();
                long midLevelCount =
                        batchResult.values().stream()
                                .filter(l -> "L4".equals(l) || "L3_OR_L4".equals(l))
                                .count();
                metrics.recordJobsClassified((int) midLevelCount);
                log.info(
                        "Gemini batch {}/{}: {} L4, {} L3, {} other (of {})",
                        batchNum,
                        totalBatches,
                        batchResult.values().stream().filter("L4"::equals).count(),
                        batchResult.values().stream().filter("L3"::equals).count(),
                        batchResult.values().stream().filter("OTHER"::equals).count(),
                        batch.size());
            }
        }

        log.info(
                "Gemini classification complete: {} classified ({} L4, {} L3, {} L3_OR_L4,"
                        + " {} OTHER), {} failed",
                levelMap.size(),
                levelMap.values().stream().filter("L4"::equals).count(),
                levelMap.values().stream().filter("L3"::equals).count(),
                levelMap.values().stream().filter("L3_OR_L4"::equals).count(),
                levelMap.values().stream().filter("OTHER"::equals).count(),
                failed.size());

        return new ClassificationResult(levelMap, failed);
    }

    /**
     * Sends a single batch to Gemini for 4-way level classification with retry support.
     *
     * @return map of job to level string, or {@code null} if the API call failed after all retries.
     */
    private Map<JobPosting, String> classifyBatchWithRetry(List<JobPosting> batch) {
        log.info("Classifying batch of {} job(s) via Gemini", batch.size());
        try {
            return retryTemplate.execute(
                    context -> {
                        if (context.getRetryCount() > 0) {
                            metrics.recordGeminiRetry();
                            log.warn(
                                    "Gemini retry attempt {} for batch of {}",
                                    context.getRetryCount(),
                                    batch.size());
                        }
                        return geminiClient.classifyLevel(batch);
                    });
        } catch (Exception e) {
            log.error(
                    "Gemini classification failed after retries, skipping batch of {}",
                    batch.size(),
                    e);
            return null;
        }
    }
}
