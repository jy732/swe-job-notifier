package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.util.CsvUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Scraper for companies using the SmartRecruiters public Postings API. */
@Slf4j
@Component
public class SmartRecruitersScraper implements JobScraper {

    private static final String API_URL =
            "https://api.smartrecruiters.com/v1/companies/{company}/postings";
    private static final int PAGE_SIZE = 100;

    private final WebClient webClient;
    private final List<String> companies;

    public SmartRecruitersScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.smartrecruiters:}") String companiesCsv) {
        this.webClient = webClientBuilder.build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("SmartRecruiters scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "smartrecruiters";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;

        try {
            while (true) {
                String url =
                        String.format(
                                "https://api.smartrecruiters.com/v1/companies/%s/postings"
                                        + "?limit=%d&offset=%d",
                                company, PAGE_SIZE, offset);
                Map<String, Object> response =
                        webClient
                                .get()
                                .uri(url)
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();

                if (response == null) {
                    break;
                }

                int totalFound = ((Number) response.getOrDefault("totalFound", 0)).intValue();
                List<Map<String, Object>> content =
                        (List<Map<String, Object>>)
                                response.getOrDefault("content", Collections.emptyList());

                for (Map<String, Object> posting : content) {
                    allJobs.add(toJobPosting(company, posting));
                }

                offset += PAGE_SIZE;
                if (offset >= totalFound || content.isEmpty()) {
                    break;
                }
            }

            log.info("SmartRecruiters [{}]: scraped {} total job(s)", company, allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape SmartRecruiters for company: {}", company, e);
            return allJobs;
        }
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(String company, Map<String, Object> posting) {
        String location = "";
        Object locationObj = posting.get("location");
        if (locationObj instanceof Map<?, ?> locMap) {
            String city = strOrEmpty(locMap.get("city"));
            String region = strOrEmpty(locMap.get("region"));
            String country = strOrEmpty(locMap.get("country"));
            location =
                    String.join(", ", city, region, country)
                            .replaceAll("^,\\s*|,\\s*$", "")
                            .replaceAll(",\\s*,", ",");
        }

        String url = "";
        Object relatedLinks = posting.get("relatedLinks");
        if (relatedLinks instanceof Map<?, ?> linksMap) {
            url = strOrEmpty(linksMap.get("careerPage"));
        }

        String description = strOrEmpty(posting.get("name"));
        Object jobAd = posting.get("jobAd");
        if (jobAd instanceof Map<?, ?> adMap) {
            Object sections = adMap.get("sections");
            if (sections instanceof Map<?, ?> sectionsMap) {
                Object companyDesc = sectionsMap.get("companyDescription");
                Object jobDesc = sectionsMap.get("jobDescription");
                Object qualifications = sectionsMap.get("qualifications");
                Object additionalInfo = sectionsMap.get("additionalInformation");
                StringBuilder sb = new StringBuilder();
                appendSection(sb, jobDesc);
                appendSection(sb, qualifications);
                appendSection(sb, additionalInfo);
                appendSection(sb, companyDesc);
                if (!sb.isEmpty()) {
                    description = sb.toString();
                }
            }
        }

        Instant postedDate = parseInstant(posting.get("releasedDate"));

        return JobPosting.builder()
                .company(company)
                .externalId(strOrEmpty(posting.get("id")))
                .title(strOrEmpty(posting.get("name")))
                .url(url)
                .location(location)
                .description(stripHtml(description))
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static void appendSection(StringBuilder sb, Object section) {
        if (section instanceof Map<?, ?> sectionMap) {
            Object text = sectionMap.get("text");
            if (text instanceof String s && !s.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(s);
            }
        }
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
