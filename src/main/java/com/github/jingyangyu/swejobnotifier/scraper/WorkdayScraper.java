package com.github.jingyangyu.swejobnotifier.scraper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.jingyangyu.swejobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.swejobnotifier.config.WorkdayProperties.WorkdayCompany;
import com.github.jingyangyu.swejobnotifier.model.JobPosting;

import lombok.extern.slf4j.Slf4j;

/**
 * Scraper for companies that use Workday as their ATS. Posts search requests to the Workday CXS
 * career site API ({@code {subdomain}.wd{N}.myworkdayjobs.com/wday/cxs/{subdomain}/{site}/jobs})
 * and paginates through all results.
 */
@Slf4j
@Component
public class WorkdayScraper implements JobScraper {

    private static final int PAGE_SIZE = 20;

    private final WebClient webClient;
    private final WorkdayProperties properties;

    public WorkdayScraper(WebClient.Builder webClientBuilder, WorkdayProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "workday";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(WorkdayCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<WorkdayCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Workday config found for company: {}", company);
            return Collections.emptyList();
        }

        WorkdayCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;
        int total;

        try {
            do {
                Map<String, Object> response = fetchPage(config, offset);
                if (response == null) {
                    break;
                }

                total = ((Number) response.getOrDefault("total", 0)).intValue();

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> postings =
                        (List<Map<String, Object>>)
                                response.getOrDefault("jobPostings", Collections.emptyList());

                for (Map<String, Object> posting : postings) {
                    allJobs.add(toJobPosting(company, config, posting));
                }

                offset += PAGE_SIZE;
            } while (offset < total);

            log.info("Workday [{}]: scraped {} total job(s)", company, allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Workday for company: {}", company, e);
            return allJobs; // return whatever we got so far
        }
    }

    private Map<String, Object> fetchPage(WorkdayCompany config, int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appliedFacets", Collections.emptyMap());
        body.put("limit", PAGE_SIZE);
        body.put("offset", offset);
        body.put("searchText", "");

        return webClient
                .post()
                .uri(config.apiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    private JobPosting toJobPosting(
            String company, WorkdayCompany config, Map<String, Object> posting) {
        String title = (String) posting.getOrDefault("title", "");
        String externalPath = (String) posting.getOrDefault("externalPath", "");
        String location = (String) posting.getOrDefault("locationsText", "");
        String description = fetchJobDescription(config, externalPath);
        return JobPosting.builder()
                .company(company)
                .externalId(externalPath)
                .title(title)
                .url(config.jobUrl(externalPath))
                .location(location)
                .description(description)
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private String fetchJobDescription(WorkdayCompany config, String externalPath) {
        try {
            String detailUrl =
                    String.format(
                            "%s/wday/cxs/%s/%s%s",
                            config.baseUrl(),
                            config.getSubdomain(),
                            config.getSite(),
                            externalPath);
            Map<String, Object> detail =
                    webClient
                            .get()
                            .uri(detailUrl)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();
            if (detail == null) {
                return "";
            }
            Map<String, Object> jobInfo = (Map<String, Object>) detail.get("jobPostingInfo");
            if (jobInfo == null) {
                return "";
            }
            Object desc = jobInfo.get("jobDescription");
            if (desc instanceof String descStr) {
                return stripHtml(descStr);
            }
            return "";
        } catch (Exception e) {
            log.debug(
                    "Failed to fetch Workday job detail for {}: {}", externalPath, e.getMessage());
            return "";
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
