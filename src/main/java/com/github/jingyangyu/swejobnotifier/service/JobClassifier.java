package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Classifies job postings using the Gemini Flash LLM to determine if they are mid-level SWE II
 * positions.
 *
 * <p>Design decisions:
 *
 * <ul>
 *   <li>Jobs are sent in batches of {@value #BATCH_SIZE} with a {@value #BATCH_DELAY_MS}ms delay
 *       between batches to stay within Gemini free-tier rate limits (15 RPM / 1M TPM).
 *   <li>Each batch is retried up to 3 times with exponential backoff (5s → 10s → 20s).
 *   <li>Failed batches return {@code null} (vs. empty list = "none matched"), so the caller can
 *       distinguish "Gemini couldn't process these" from "Gemini said none are mid-level".
 *   <li>When no API key is configured, all pre-filtered jobs are returned as approved — this lets
 *       the app run without Gemini (useful for development/testing).
 *   <li>Description snippets are truncated to 500 chars to stay within token limits on the free
 *       tier. This is a trade-off: some YOE requirements may be cut off, but it keeps costs at $0.
 * </ul>
 */
@Slf4j
@Service
public class JobClassifier {

    private static final int BATCH_SIZE = 50;
    private static final long BATCH_DELAY_MS = 5000; // 5s between batches to respect rate limits

    private static final String SYSTEM_PROMPT =
            "Classify each job as Y (mid-level SWE) or N. "
                    + "Mid-level = 2-5 years experience, equivalent to SWE II / L4 / E4 / IC3. "
                    + "Include: Backend/Frontend/Full Stack/Platform/Infrastructure/Mobile engineer"
                    + " at mid-level, titles without level qualifier if description suggests 2-5"
                    + " YOE. "
                    + "Exclude: Senior/Staff/Principal, Junior/Intern/New Grad, 6+ YOE, 0-1 YOE,"
                    + " managers, non-engineering roles. "
                    + "Response format: 1:Y\\n2:N\\n3:Y";

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final RetryTemplate retryTemplate;

    public JobClassifier(
            WebClient.Builder webClientBuilder,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-2.0-flash}") String model) {
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;
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
        if (apiKey == null || apiKey.isBlank()) {
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
            log.info("Waiting {}ms before Gemini batch {}/{}", BATCH_DELAY_MS, batchNum, totalBatches);
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
        List<JobPosting> result = classifyBatch(batch);
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

    /** Result of Gemini classification, separating approved jobs from failed (unprocessed) ones. */
    @Getter
    public static class ClassificationResult {
        private final List<JobPosting> approved;
        private final List<JobPosting> failed;

        public ClassificationResult(List<JobPosting> approved, List<JobPosting> failed) {
            this.approved = approved;
            this.failed = failed;
        }
    }

    /**
     * Sends a single batch to Gemini with retry support.
     *
     * @return list of approved jobs, empty list if none matched, or {@code null} if the API call
     *     failed after all retries (signals the caller to skip persistence for this batch).
     */
    @SuppressWarnings("unchecked")
    private List<JobPosting> classifyBatch(List<JobPosting> batch) {
        log.info("Classifying batch of {} job(s) via Gemini", batch.size());
        try {
            return retryTemplate.execute(context -> {
                if (context.getRetryCount() > 0) {
                    log.warn("Gemini retry attempt {} for batch of {}",
                            context.getRetryCount(), batch.size());
                }
                Map<String, Object> response = callGeminiApi(buildUserPrompt(batch));
                return parseResponse(response, batch);
            });
        } catch (Exception e) {
            log.error("Gemini classification failed after retries, skipping batch of {}",
                    batch.size(), e);
            return null;
        }
    }

    private Map<String, Object> callGeminiApi(String userPrompt) {
        Map<String, Object> requestBody =
                Map.of(
                        "system_instruction",
                        Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                        "contents",
                        List.of(Map.of("role", "user",
                                "parts", List.of(Map.of("text", userPrompt)))));

        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                model, apiKey);

        return webClient
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    private String buildUserPrompt(List<JobPosting> batch) {
        StringBuilder sb = new StringBuilder("Classify these job postings:\n\n");
        for (int i = 0; i < batch.size(); i++) {
            JobPosting job = batch.get(i);
            String descSnippet =
                    job.getDescription() != null && job.getDescription().length() > 500
                            ? job.getDescription().substring(0, 500)
                            : (job.getDescription() != null ? job.getDescription() : "");
            sb.append(String.format("%d. Title: %s\n", i + 1, job.getTitle()));
            sb.append(String.format("   Description: %s\n\n", descSnippet));
        }
        return sb.toString();
    }

    /**
     * Parses Gemini's response text into a list of approved jobs.
     *
     * <p>Expected response format (one per line): {@code "1:Y\n2:N\n3:Y"} where the number is the
     * 1-indexed position in the batch and Y/N is the classification. Malformed lines are silently
     * skipped — partial results are better than failing the entire batch.
     */
    @SuppressWarnings("unchecked")
    private List<JobPosting> parseResponse(Map<String, Object> response, List<JobPosting> batch) {
        if (response == null) {
            return Collections.emptyList();
        }

        try {
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return Collections.emptyList();
            }

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            String text = parts.get(0).get("text").toString().trim();

            log.debug("Gemini raw response: {}", text);

            // Parse "1:Y\n2:N\n3:Y" format — convert 1-indexed response to 0-indexed batch
            List<JobPosting> result = new ArrayList<>();
            String[] lines = text.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts2 = line.split(":");
                if (parts2.length == 2) {
                    int index = Integer.parseInt(parts2[0].trim()) - 1;
                    String classification = parts2[1].trim().toUpperCase();
                    if ("Y".equals(classification) && index >= 0 && index < batch.size()) {
                        result.add(batch.get(index));
                    }
                }
            }
            log.info(
                    "Gemini classified {}/{} job(s) as mid-level SWE", result.size(), batch.size());
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response", e);
            return Collections.emptyList();
        }
    }
}
