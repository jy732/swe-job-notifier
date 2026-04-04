package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Low-level client for the Gemini generativeLanguage API.
 *
 * <p>Handles prompt construction, HTTP communication, and response parsing. Does NOT handle
 * batching, rate limiting, or retry — that orchestration lives in {@link JobClassifier}.
 *
 * <p>The system prompt instructs Gemini to respond in a strict {@code "1:Y\n2:N\n3:Y"} format.
 * Description snippets are truncated to 500 chars to stay within free-tier token limits — a
 * trade-off where some YOE requirements may be cut off, but keeps API costs at $0.
 */
@Slf4j
@Component
public class GeminiClient {

    private static final int DESC_SNIPPET_LENGTH = 500;

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

    public GeminiClient(
            WebClient.Builder webClientBuilder,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-2.0-flash}") String model) {
        this.webClient = webClientBuilder.build();
        this.apiKey = apiKey;
        this.model = model;

        if (apiKey == null || apiKey.isBlank()) {
            log.error(
                    "██ GEMINI API KEY NOT CONFIGURED ██ "
                            + "— all jobs will bypass classification (no filtering by level)");
        } else {
            log.info("Gemini configured: model={}", model);
        }
    }

    /** Returns true if the Gemini API key is configured and non-blank. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Sends a batch of jobs to Gemini for classification and parses the response.
     *
     * @return list of jobs classified as mid-level (Y), empty list if none matched, or {@code null}
     *     if the API call failed (signals caller to retry).
     */
    @SuppressWarnings("unchecked")
    public List<JobPosting> classify(List<JobPosting> batch) {
        Map<String, Object> response = callApi(buildPrompt(batch));
        return parseResponse(response, batch);
    }

    /** Builds the numbered user prompt with title + truncated description for each job. */
    String buildPrompt(List<JobPosting> batch) {
        StringBuilder sb = new StringBuilder("Classify these job postings:\n\n");
        for (int i = 0; i < batch.size(); i++) {
            JobPosting job = batch.get(i);
            String desc = job.getDescription() != null ? job.getDescription() : "";
            String snippet =
                    desc.length() > DESC_SNIPPET_LENGTH
                            ? desc.substring(0, DESC_SNIPPET_LENGTH)
                            : desc;
            sb.append(String.format("%d. Title: %s\n", i + 1, job.getTitle()));
            sb.append(String.format("   Description: %s\n\n", snippet));
        }
        return sb.toString();
    }

    private Map<String, Object> callApi(String userPrompt) {
        Map<String, Object> requestBody =
                Map.of(
                        "system_instruction",
                        Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                        "contents",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "parts",
                                        List.of(Map.of("text", userPrompt)))));

        String url =
                String.format(
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

    /**
     * Parses Gemini's response text into a list of approved jobs.
     *
     * <p>Expected format (one per line): {@code "1:Y\n2:N\n3:Y"} where the number is the 1-indexed
     * position in the batch. Malformed lines are silently skipped — partial results are better than
     * failing the entire batch.
     */
    @SuppressWarnings("unchecked")
    List<JobPosting> parseResponse(Map<String, Object> response, List<JobPosting> batch) {
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
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split(":");
                if (tokens.length == 2) {
                    int index = Integer.parseInt(tokens[0].trim()) - 1;
                    String classification = tokens[1].trim().toUpperCase();
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
