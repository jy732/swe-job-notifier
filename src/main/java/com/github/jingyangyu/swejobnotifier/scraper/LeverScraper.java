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

/** Scraper for companies using the Lever Postings API. */
@Slf4j
@Component
public class LeverScraper implements JobScraper {

    private static final String API_URL = "https://api.lever.co/v0/postings/{company}";

    private final WebClient webClient;
    private final List<String> companies;

    public LeverScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.lever:}") String companiesCsv) {
        this.webClient = webClientBuilder.build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("Lever scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "lever";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        try {
            List<Map<String, Object>> postings =
                    webClient
                            .get()
                            .uri(API_URL, company)
                            .retrieve()
                            .bodyToMono(
                                    new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                            .block();

            if (postings == null) {
                return Collections.emptyList();
            }

            return postings.stream()
                    .map(
                            posting -> {
                                String location = "";
                                Object categories = posting.get("categories");
                                if (categories instanceof Map<?, ?> catMap) {
                                    Object loc = catMap.get("location");
                                    if (loc != null) {
                                        location = loc.toString();
                                    }
                                }

                                Instant postedDate = null;
                                Object createdAt = posting.get("createdAt");
                                if (createdAt instanceof Number num) {
                                    postedDate = Instant.ofEpochMilli(num.longValue());
                                }

                                return JobPosting.builder()
                                        .company(company)
                                        .externalId(
                                                posting.get("id") != null
                                                        ? posting.get("id").toString()
                                                        : "")
                                        .title(
                                                posting.get("text") != null
                                                        ? posting.get("text").toString()
                                                        : "")
                                        .url(
                                                posting.get("hostedUrl") != null
                                                        ? posting.get("hostedUrl").toString()
                                                        : "")
                                        .location(location)
                                        .description(
                                                posting.get("descriptionPlain") != null
                                                        ? posting.get("descriptionPlain").toString()
                                                        : "")
                                        .postedDate(postedDate)
                                        .detectedAt(Instant.now())
                                        .build();
                            })
                    .toList();
        } catch (Exception e) {
            log.error("Failed to scrape Lever for company: {}", company, e);
            return Collections.emptyList();
        }
    }
}
