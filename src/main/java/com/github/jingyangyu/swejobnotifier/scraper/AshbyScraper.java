package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.util.CsvUtil;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Scraper for companies using the Ashby Posting API. */
@Slf4j
@Component
public class AshbyScraper implements JobScraper {

    private static final String API_URL = "https://api.ashbyhq.com/posting-api/job-board/{token}";

    private final WebClient webClient;
    private final List<String> companies;

    public AshbyScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.ashby:}") String companiesCsv) {
        this.webClient = webClientBuilder.build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("Ashby scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "ashby";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches all jobs from the Ashby Posting API ({@code /posting-api/job-board/{token}}) in a
     * single request. No pagination — Ashby returns the full job board. Secondary locations are
     * concatenated with semicolons. Prefers {@code descriptionPlain} over {@code descriptionHtml}
     * (HTML is stripped as fallback). On failure, returns an empty list.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        try {
            Map<String, Object> response =
                    webClient
                            .get()
                            .uri(API_URL, company)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (response == null || !response.containsKey("jobs")) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> jobs = (List<Map<String, Object>>) response.get("jobs");

            List<JobPosting> results =
                    jobs.stream().map(job -> toJobPosting(company, job)).toList();
            log.info("Ashby [{}]: scraped {} job(s)", company, results.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to scrape Ashby for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    private JobPosting toJobPosting(String company, Map<String, Object> job) {
        String location = strOrEmpty(job.get("location"));
        Object secondaryLocations = job.get("secondaryLocations");
        if (secondaryLocations instanceof List<?> locs && !locs.isEmpty()) {
            StringBuilder sb = new StringBuilder(location);
            for (Object loc : locs) {
                if (loc instanceof Map<?, ?> locMap) {
                    String locName = strOrEmpty(locMap.get("location"));
                    if (!locName.isEmpty()) {
                        if (!sb.isEmpty()) sb.append("; ");
                        sb.append(locName);
                    }
                }
            }
            location = sb.toString();
        }

        String description = strOrEmpty(job.get("descriptionPlain"));
        if (description.isEmpty()) {
            String descHtml = strOrEmpty(job.get("descriptionHtml"));
            if (!descHtml.isEmpty()) {
                description = stripHtml(descHtml);
            }
        }

        Instant postedDate = parseInstant(job.get("publishedAt"));

        return JobPosting.builder()
                .company(company)
                .externalId(strOrEmpty(job.get("id")))
                .title(strOrEmpty(job.get("title")))
                .url(strOrEmpty(job.get("jobUrl")))
                .location(location)
                .description(description)
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
