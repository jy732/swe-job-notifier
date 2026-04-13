package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.swejobnotifier.config.OracleCloudProperties.OracleCloudCompany;
import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for companies using Oracle Cloud HCM (formerly Taleo). Fetches job requisitions from the
 * Oracle Recruiting Cloud REST API.
 */
@Slf4j
@Component
public class OracleCloudScraper implements JobScraper {

    private static final int PAGE_SIZE = 25;

    private final WebClient webClient;
    private final OracleCloudProperties properties;

    public OracleCloudScraper(
            WebClient.Builder webClientBuilder, OracleCloudProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Oracle Cloud scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "oraclecloud";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(OracleCloudCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates through the Oracle Recruiting Cloud REST API ({@code
     * /hcmRestApi/resources/latest/recruitingCEJobRequisitions}) in batches of {@value #PAGE_SIZE}.
     * The API URL is constructed from per-company config (subdomain, region, site number). Each
     * company config maps to a specific Oracle Cloud HCM instance. On failure mid-pagination,
     * returns partial results.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        Optional<OracleCloudCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Oracle Cloud config found for company: {}", company);
            return Collections.emptyList();
        }

        OracleCloudCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;

        try {
            while (true) {
                Map<String, Object> response = fetchPage(config, offset);
                if (response == null) {
                    break;
                }

                List<Map<String, Object>> items =
                        (List<Map<String, Object>>)
                                response.getOrDefault("items", Collections.emptyList());
                if (items.isEmpty()) {
                    break;
                }

                // The first item contains requisitionList and TotalJobsCount
                Map<String, Object> wrapper = items.get(0);
                int totalJobs = ((Number) wrapper.getOrDefault("TotalJobsCount", 0)).intValue();

                List<Map<String, Object>> requisitions =
                        (List<Map<String, Object>>)
                                wrapper.getOrDefault("requisitionList", Collections.emptyList());

                for (Map<String, Object> req : requisitions) {
                    allJobs.add(toJobPosting(company, config, req));
                }

                offset += PAGE_SIZE;
                if (offset >= totalJobs || requisitions.isEmpty()) {
                    break;
                }
            }

            log.info("Oracle Cloud [{}]: scraped {} total job(s)", company, allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Oracle Cloud for company: {}", company, e);
            return allJobs;
        }
    }

    private Map<String, Object> fetchPage(OracleCloudCompany config, int offset) {
        return webClient
                .get()
                .uri(config.apiUrl(PAGE_SIZE, offset))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    private JobPosting toJobPosting(
            String company, OracleCloudCompany config, Map<String, Object> req) {
        Object reqId = req.getOrDefault("Id", req.get("RequisitionId"));
        String title = strOrEmpty(req.get("Title"));
        String location = strOrEmpty(req.get("PrimaryLocation"));
        String description = stripHtml(strOrEmpty(req.get("ExternalDescriptionStr")));
        String qualifications = stripHtml(strOrEmpty(req.get("ExternalQualificationsStr")));
        if (!qualifications.isEmpty()) {
            description = description + " " + qualifications;
        }

        Instant postedDate = parseDate(req.get("PostedDate"));

        return JobPosting.builder()
                .company(company)
                .externalId(String.valueOf(reqId))
                .title(title)
                .url(config.jobUrl(reqId))
                .location(location)
                .description(description.trim())
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parseDate(Object value) {
        if (value == null) return null;
        try {
            String str = value.toString();
            // Oracle Cloud dates can be "2024-06-15" or ISO instant
            if (str.length() == 10 && str.charAt(4) == '-') {
                return LocalDate.parse(str).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(str);
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
