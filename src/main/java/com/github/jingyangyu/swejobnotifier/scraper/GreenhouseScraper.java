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

            return jobs.stream()
                    .map(
                            job -> {
                                String locationName = "";
                                Object locationObj = job.get("location");
                                if (locationObj instanceof Map<?, ?> locMap) {
                                    Object name = locMap.get("name");
                                    if (name != null) {
                                        locationName = name.toString();
                                    }
                                }

                                String content =
                                        job.get("content") != null
                                                ? job.get("content")
                                                        .toString()
                                                        .replaceAll("<[^>]+>", "")
                                                : "";

                                String updatedAt =
                                        job.get("updated_at") != null
                                                ? job.get("updated_at").toString()
                                                : null;
                                Instant postedDate = null;
                                if (updatedAt != null) {
                                    try {
                                        postedDate = Instant.parse(updatedAt);
                                    } catch (Exception e) {
                                        // ignore parse errors
                                    }
                                }

                                return JobPosting.builder()
                                        .company(company)
                                        .externalId(String.valueOf(job.get("id")))
                                        .title(
                                                job.get("title") != null
                                                        ? job.get("title").toString()
                                                        : "")
                                        .url(
                                                job.get("absolute_url") != null
                                                        ? job.get("absolute_url").toString()
                                                        : "")
                                        .location(locationName)
                                        .description(content)
                                        .postedDate(postedDate)
                                        .detectedAt(Instant.now())
                                        .build();
                            })
                    .toList();
        } catch (Exception e) {
            log.error("Failed to scrape Greenhouse for company: {}", company, e);
            return Collections.emptyList();
        }
    }
}
