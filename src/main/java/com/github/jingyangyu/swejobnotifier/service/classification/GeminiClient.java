package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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

    private static final int SIGNAL_WINDOW = 200;
    private static final int MAX_SIGNALS = 3;

    /** Signal keywords to search for in job descriptions. */
    private static final List<String> SIGNAL_KEYWORDS =
            List.of(
                    "years",
                    "pursuing",
                    "graduating",
                    "graduation",
                    "new grad",
                    "university",
                    "college");

    /** Pattern to strip HTML tags from description snippets. */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final String SYSTEM_PROMPT =
            "Classify each job as Y (mid-level SWE) or N based on the title and signals "
                    + "extracted from the job description. "
                    + "Mid-level = 2-5 years experience, equivalent to SWE II / L4 / E4 / IC3. "
                    + "Include: Backend/Frontend/Full Stack/Platform/Infrastructure/Mobile engineer"
                    + " at mid-level, titles without level qualifier if signals suggest 2-5 YOE. "
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

    private static final String LEVEL_SYSTEM_PROMPT =
            "Classify each job's level based on the title and extracted signals. "
                    + "L3 = entry-level / new grad / junior. Signals: pursuing or recently completed "
                    + "degree, graduating soon, new grad program, university/college hiring, "
                    + "0-1 years experience. "
                    + "L4 = mid-level, 2-5 years experience, SWE II / L4 / E4 / IC3. "
                    + "L3_OR_L4 = ambiguous, could be either entry-level or mid-level. "
                    + "OTHER = senior/staff/principal, management, non-engineering, 6+ YOE. "
                    + "Response format: 1:L4\\n2:L3\\n3:OTHER\\n4:L3_OR_L4";

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

    /** Builds the numbered user prompt with title + extracted signal snippets from the JD. */
    String buildPrompt(List<JobPosting> batch) {
        StringBuilder sb = new StringBuilder("Classify these job postings:\n\n");
        int withSignals = 0;
        for (int i = 0; i < batch.size(); i++) {
            JobPosting job = batch.get(i);
            String signals = extractSignals(job.getDescription());
            if (!"(none)".equals(signals)) {
                withSignals++;
            }
            log.debug("Signal extraction [{}] {}: {}", job.getCompany(), job.getTitle(), signals);
            sb.append(String.format("%d. Title: %s\n", i + 1, job.getTitle()));
            sb.append(String.format("   Signals: %s\n\n", signals));
        }
        log.info(
                "Signal extraction: {}/{} job(s) had signals, {} had none",
                withSignals,
                batch.size(),
                batch.size() - withSignals);
        return sb.toString();
    }

    /**
     * Extracts relevant signal snippets from a job description by searching for keywords like
     * "years", "pursuing", "graduating", etc. Returns up to {@value #MAX_SIGNALS} snippets of
     * ~{@value #SIGNAL_WINDOW} chars each, or "(none)" if no keywords found.
     */
    static String extractSignals(String description) {
        if (description == null || description.isBlank()) {
            return "(none)";
        }
        // Strip HTML tags for cleaner snippets
        String clean = HTML_TAG_PATTERN.matcher(description).replaceAll(" ");
        String lower = clean.toLowerCase(Locale.ROOT);

        Set<String> snippets = new LinkedHashSet<>();
        for (String keyword : SIGNAL_KEYWORDS) {
            int idx = 0;
            while (idx < lower.length() && snippets.size() < MAX_SIGNALS) {
                int pos = lower.indexOf(keyword, idx);
                if (pos == -1) break;

                int start = Math.max(0, pos - SIGNAL_WINDOW / 2);
                int end = Math.min(clean.length(), pos + keyword.length() + SIGNAL_WINDOW / 2);
                String snippet = clean.substring(start, end).trim().replaceAll("\\s+", " ");
                snippets.add("\"" + snippet + "\"");

                idx = pos + keyword.length();
            }
            if (snippets.size() >= MAX_SIGNALS) break;
        }

        return snippets.isEmpty() ? "(none)" : String.join(" | ", snippets);
    }

    /**
     * Sends a batch of jobs to Gemini for 4-way level classification (shadow flow).
     *
     * @return map of job to level string (L3/L4/L3_OR_L4/OTHER), or {@code null} if API call
     *     failed.
     */
    @SuppressWarnings("unchecked")
    public Map<JobPosting, String> classifyLevel(List<JobPosting> batch) {
        Map<String, Object> response = callApi(buildPrompt(batch), LEVEL_SYSTEM_PROMPT);
        return parseLevelResponse(response, batch);
    }

    @SuppressWarnings("unchecked")
    Map<JobPosting, String> parseLevelResponse(
            Map<String, Object> response, List<JobPosting> batch) {
        if (response == null) {
            return null;
        }
        try {
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            String text = parts.get(0).get("text").toString().trim();

            log.debug("Gemini level raw response: {}", text);

            Set<String> validLevels = Set.of("L3", "L4", "L3_OR_L4", "OTHER");
            Map<JobPosting, String> result = new HashMap<>();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split(":");
                if (tokens.length == 2) {
                    int index = Integer.parseInt(tokens[0].trim()) - 1;
                    String level = tokens[1].trim().toUpperCase();
                    if (validLevels.contains(level) && index >= 0 && index < batch.size()) {
                        result.put(batch.get(index), level);
                    }
                }
            }
            log.info("Gemini level-classified {}/{} job(s)", result.size(), batch.size());
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini level response", e);
            return null;
        }
    }

    private Map<String, Object> callApi(String userPrompt) {
        return callApi(userPrompt, SYSTEM_PROMPT);
    }

    private Map<String, Object> callApi(String userPrompt, String systemPrompt) {
        Map<String, Object> requestBody =
                Map.of(
                        "system_instruction",
                        Map.of("parts", List.of(Map.of("text", systemPrompt))),
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
