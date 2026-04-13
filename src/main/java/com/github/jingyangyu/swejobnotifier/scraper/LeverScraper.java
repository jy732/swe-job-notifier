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

    /**
     * {@inheritDoc}
     *
     * <p>Fetches all postings from the Lever Postings API ({@code /v0/postings/{company}}) in a
     * single request. No pagination — Lever returns the full list. Posted dates are parsed from
     * epoch milliseconds ({@code createdAt}). Location is extracted from the {@code categories}
     * map. On failure, returns an empty list.
     */
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

            return postings.stream().map(posting -> toJobPosting(company, posting)).toList();
        } catch (Exception e) {
            log.error("Failed to scrape Lever for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    private JobPosting toJobPosting(String company, Map<String, Object> posting) {
        String location = "";
        Object categories = posting.get("categories");
        if (categories instanceof Map<?, ?> catMap && catMap.get("location") != null) {
            location = catMap.get("location").toString();
        }

        Instant postedDate = null;
        Object createdAt = posting.get("createdAt");
        if (createdAt instanceof Number num) {
            postedDate = Instant.ofEpochMilli(num.longValue());
        }

        return JobPosting.builder()
                .company(company)
                .externalId(strOrEmpty(posting.get("id")))
                .title(strOrEmpty(posting.get("text")))
                .url(strOrEmpty(posting.get("hostedUrl")))
                .location(location)
                .description(strOrEmpty(posting.get("descriptionPlain")))
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
