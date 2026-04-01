package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates job classification via Gemini with batching, rate limiting, and retry.
 *
 * <p>This class handles the "how" of classification (batching, pacing, error handling) while
 * {@link GeminiClient} handles the "what" (API calls, prompt building, response parsing).
 *
 * <p>Design decisions:
 *
 * <ul>
 *   <li>Jobs are sent in batches of {@value #BATCH_SIZE} with a {@value #BATCH_DELAY_MS}ms delay
 *       between batches to stay within Gemini free-tier rate limits (15 RPM / 1M TPM).
 *   <li>Each batch is retried up to 3 times with exponential backoff (5s -> 10s -> 20s).
 *   <li>When no API key is configured, all pre-filtered jobs are returned as approved — this lets
 *       the app run in dev mode without Gemini, relying solely on local title filters.
 * </ul>
 */
@Slf4j
@Service
public class JobClassifier {

    private static final int BATCH_SIZE = 50;
    private static final long BATCH_DELAY_MS = 5000;

    private final GeminiClient geminiClient;
    private final RetryTemplate retryTemplate;

    public JobClassifier(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
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
            log.warn("Gemini API key not configured — returning all {} jobs unclassified",
                    jobs.size());
            return new ClassificationResult(jobs, Collections.emptyList());
        }

        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info("Gemini classification: {} job(s) in {} batch(es)", jobs.size(), totalBatches);
        List<JobPosting> classified = new ArrayList<>();
        List<JobPosting> failed = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            if (!waitBetweenBatches(i, batchNum, totalBatches)) {
                failed.addAll(jobs.subList(i, jobs.size()));
                break;
            }
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            processBatch(batch, batchNum, totalBatches, classified, failed);
        }

        log.info("Gemini classification complete: {}/{} mid-level, {} failed",
                classified.size(), jobs.size(), failed.size());
        return new ClassificationResult(classified, failed);
    }

    /** Waits between batches to respect rate limits. Returns false if interrupted. */
    private boolean waitBetweenBatches(int offset, int batchNum, int totalBatches) {
        if (offset == 0) return true;
        try {
            log.info("Waiting {}ms before Gemini batch {}/{}",
                    BATCH_DELAY_MS, batchNum, totalBatches);
            Thread.sleep(BATCH_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini classification interrupted at batch {}/{}", batchNum, totalBatches);
            return false;
        }
    }

    /** Classifies a single batch and appends results to classified/failed lists. */
    private void processBatch(List<JobPosting> batch, int batchNum, int totalBatches,
            List<JobPosting> classified, List<JobPosting> failed) {
        List<JobPosting> result = classifyBatchWithRetry(batch);
        if (result == null) {
            failed.addAll(batch);
            log.warn("Gemini batch {}/{} failed — {} job(s) will retry next poll",
                    batchNum, totalBatches, batch.size());
        } else {
            classified.addAll(result);
            log.info("Gemini batch {}/{}: {}/{} mid-level (running total: {})",
                    batchNum, totalBatches, result.size(), batch.size(), classified.size());
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
            return retryTemplate.execute(context -> {
                if (context.getRetryCount() > 0) {
                    log.warn("Gemini retry attempt {} for batch of {}",
                            context.getRetryCount(), batch.size());
                }
                return geminiClient.classify(batch);
            });
        } catch (Exception e) {
            log.error("Gemini classification failed after retries, skipping batch of {}",
                    batch.size(), e);
            return null;
        }
    }
}
