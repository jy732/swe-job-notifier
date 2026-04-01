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
     * Classifies a list of job postings, returning approved jobs and failed (unprocessed) jobs
     * separately so the caller can decide what to persist.
     *
     * @param jobs the pre-filtered job postings to classify
     * @return result containing approved mid-level jobs and jobs that failed classification
     */
    public ClassificationResult classify(List<JobPosting> jobs) {
        if (jobs.isEmpty()) {
            return new ClassificationResult(Collections.emptyList(), Collections.emptyList());
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn(
                    "Gemini API key not configured — returning all {} pre-filtered jobs"
                            + " unclassified",
                    jobs.size());
            return new ClassificationResult(jobs, Collections.emptyList());
        }

        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info("Gemini classification: {} job(s) in {} batch(es)", jobs.size(), totalBatches);
        List<JobPosting> classified = new ArrayList<>();
        List<JobPosting> failed = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            if (i > 0) {
                try {
                    log.info(
                            "Waiting {}ms before Gemini batch {}/{}",
                            BATCH_DELAY_MS,
                            batchNum,
                            totalBatches);
                    Thread.sleep(BATCH_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn(
                            "Gemini classification interrupted at batch {}/{}",
                            batchNum,
                            totalBatches);
                    // Add remaining jobs as failed
                    failed.addAll(jobs.subList(i, jobs.size()));
                    break;
                }
            }
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            List<JobPosting> result = classifyBatch(batch);
            if (result == null) {
                // Batch failed — don't persist, retry next poll
                failed.addAll(batch);
                log.warn(
                        "Gemini batch {}/{} failed — {} job(s) will retry next poll",
                        batchNum,
                        totalBatches,
                        batch.size());
            } else {
                classified.addAll(result);
                log.info(
                        "Gemini batch {}/{}: {}/{} classified as mid-level (running total: {})",
                        batchNum,
                        totalBatches,
                        result.size(),
                        batch.size(),
                        classified.size());
            }
        }

        log.info(
                "Gemini classification complete: {}/{} mid-level, {} failed",
                classified.size(),
                jobs.size(),
                failed.size());
        return new ClassificationResult(classified, failed);
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

    @SuppressWarnings("unchecked")
    private List<JobPosting> classifyBatch(List<JobPosting> batch) {
        log.info("Classifying batch of {} job(s) via Gemini", batch.size());
        try {
            return retryTemplate.execute(
                    context -> {
                        if (context.getRetryCount() > 0) {
                            log.warn(
                                    "Gemini retry attempt {} for batch of {}",
                                    context.getRetryCount(),
                                    batch.size());
                        }
                        String userPrompt = buildUserPrompt(batch);

                        Map<String, Object> requestBody =
                                Map.of(
                                        "system_instruction",
                                                Map.of(
                                                        "parts",
                                                        List.of(Map.of("text", SYSTEM_PROMPT))),
                                        "contents",
                                                List.of(
                                                        Map.of(
                                                                "role",
                                                                "user",
                                                                "parts",
                                                                List.of(
                                                                        Map.of(
                                                                                "text",
                                                                                userPrompt)))));

                        String url =
                                String.format(
                                        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                                        model, apiKey);

                        Map<String, Object> response =
                                webClient
                                        .post()
                                        .uri(url)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .bodyToMono(
                                                new ParameterizedTypeReference<
                                                        Map<String, Object>>() {})
                                        .block();

                        return parseResponse(response, batch);
                    });
        } catch (Exception e) {
            log.error(
                    "Gemini classification failed after retries, skipping batch of {}",
                    batch.size(),
                    e);
            return null; // null signals failure (vs empty = none matched)
        }
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
