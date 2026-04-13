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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Scraper for companies using the Greenhouse Boards API. */
@Slf4j
@Component
public class GreenhouseScraper implements JobScraper {

    private static final String API_URL =
            "https://boards-api.greenhouse.io/v1/boards/{company}/jobs?content=true";

    private final WebClient webClient;
    private final List<String> companies;

    public GreenhouseScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.greenhouse:}") String companiesCsv) {
        this.webClient = webClientBuilder.build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("Greenhouse scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "greenhouse";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches all jobs from the Greenhouse Boards API in a single request ({@code
     * /v1/boards/{company}/jobs?content=true}). No pagination needed — Greenhouse returns the full
     * job list. The {@code content=true} param includes HTML job descriptions, which are stripped
     * to plain text for signal extraction. On failure, returns an empty list.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        try {
            Map<String, Object> response =
                    webClient
                            .get()
                            .uri(API_URL, company)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (response == null || !response.containsKey("jobs")) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> jobs = (List<Map<String, Object>>) response.get("jobs");
            return jobs.stream().map(job -> toJobPosting(company, job)).toList();
        } catch (Exception e) {
            log.error("Failed to scrape Greenhouse for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    private JobPosting toJobPosting(String company, Map<String, Object> job) {
        String locationName = "";
        Object locationObj = job.get("location");
        if (locationObj instanceof Map<?, ?> locMap && locMap.get("name") != null) {
            locationName = locMap.get("name").toString();
        }

        String content =
                job.get("content") != null
                        ? job.get("content").toString().replaceAll("<[^>]+>", "")
                        : "";

        Instant postedDate = parseInstant(job.get("updated_at"));

        return JobPosting.builder()
                .company(company)
                .externalId(String.valueOf(job.get("id")))
                .title(strOrEmpty(job.get("title")))
                .url(strOrEmpty(job.get("absolute_url")))
                .location(locationName)
                .description(content)
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
}
