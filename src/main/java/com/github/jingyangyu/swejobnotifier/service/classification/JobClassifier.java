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
 * <p>Design decisions:
 *
 * <ul>
 *   <li>Jobs are sent in batches of {@value #BATCH_SIZE} to stay within token limits.
 *   <li>Each batch is retried up to 3 times with exponential backoff (5s -> 10s -> 20s).
 *   <li>When no API key is configured, all pre-filtered jobs are returned as approved — this lets
 *       the app run in dev mode without Gemini, relying solely on local title filters.
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
     * Classifies a list of job postings via Gemini in batches.
     *
     * <p>Returns a {@link ClassificationResult} separating approved jobs from failed ones. The
     * caller uses this to decide persistence: approved and rejected jobs are persisted for dedup,
     * while failed jobs are left unpersisted so they retry on the next poll.
     *
     * <p>If no API key is configured, all jobs are returned as "approved" — this is intentional so
     * the app can run in dev mode without Gemini, relying solely on local title filters.
     */
    public ClassificationResult classify(List<JobPosting> jobs) {
        if (jobs.isEmpty()) {
            return new ClassificationResult(Collections.emptyList(), Collections.emptyList());
        }
        if (!geminiClient.isConfigured()) {
            log.warn(
                    "Gemini API key not configured — returning all {} jobs unclassified",
                    jobs.size());
            return new ClassificationResult(jobs, Collections.emptyList());
        }

        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info("Gemini classification: {} job(s) in {} batch(es)", jobs.size(), totalBatches);
        List<JobPosting> classified = new ArrayList<>();
        List<JobPosting> failed = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            processBatch(batch, batchNum, totalBatches, classified, failed);
        }

        log.info(
                "Gemini classification complete: {}/{} mid-level, {} failed",
                classified.size(),
                jobs.size(),
                failed.size());

        // Shadow: run 4-way level classification on the same jobs
        Map<JobPosting, String> levelMap = classifyLevel(jobs);

        return new ClassificationResult(classified, failed, levelMap);
    }

    /**
     * Shadow 4-way level classification via a separate Gemini call.
     *
     * <p>Runs independently of the primary Y/N classification. Failures are logged but do not
     * affect the primary flow — levelMap will be empty on failure.
     */
    private Map<JobPosting, String> classifyLevel(List<JobPosting> jobs) {
        if (!geminiClient.isConfigured()) {
            return Collections.emptyMap();
        }
        Map<JobPosting, String> levelMap = new HashMap<>();
        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info(
                "Shadow level classification: {} job(s) in {} batch(es)",
                jobs.size(),
                totalBatches);

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            try {
                Map<JobPosting, String> result =
                        retryTemplate.execute(
                                context -> {
                                    if (context.getRetryCount() > 0) {
                                        log.warn(
                                                "Shadow level retry attempt {} for batch of {}",
                                                context.getRetryCount(),
                                                batch.size());
                                    }
                                    return geminiClient.classifyLevel(batch);
                                });
                if (result != null) {
                    levelMap.putAll(result);
                    log.info(
                            "Shadow level batch {}/{}: classified {}",
                            batchNum,
                            totalBatches,
                            result.size());
                } else {
                    log.warn("Shadow level batch {}/{} returned null", batchNum, totalBatches);
                }
            } catch (Exception e) {
                log.warn(
                        "Shadow level batch {}/{} failed after retries: {}",
                        batchNum,
                        totalBatches,
                        e.getMessage());
            }
        }
        return levelMap;
    }

    /** Classifies a single batch and appends results to classified/failed lists. */
    private void processBatch(
            List<JobPosting> batch,
            int batchNum,
            int totalBatches,
            List<JobPosting> classified,
            List<JobPosting> failed) {
        List<JobPosting> result = classifyBatchWithRetry(batch);
        if (result == null) {
            failed.addAll(batch);
            metrics.recordGeminiFail();
            log.warn(
                    "Gemini batch {}/{} failed — {} job(s) will retry next poll",
                    batchNum,
                    totalBatches,
                    batch.size());
        } else {
            classified.addAll(result);
            metrics.recordGeminiSuccess();
            metrics.recordJobsClassified(result.size());
            log.info(
                    "Gemini batch {}/{}: {}/{} mid-level (running total: {})",
                    batchNum,
                    totalBatches,
                    result.size(),
                    batch.size(),
                    classified.size());
        }
    }

    /**
     * Sends a single batch to Gemini with retry support.
     *
     * @return list of approved jobs, empty list if none matched, or {@code null} if the API call
     *     failed after all retries (signals the caller to skip persistence for this batch).
     */
    private List<JobPosting> classifyBatchWithRetry(List<JobPosting> batch) {
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
                        return geminiClient.classify(batch);
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
