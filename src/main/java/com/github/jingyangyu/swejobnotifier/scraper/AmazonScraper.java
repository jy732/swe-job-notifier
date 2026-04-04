package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Amazon Jobs ({@code amazon.jobs/en/search.json}).
 *
 * <p>Amazon exposes a public JSON search API with offset-based pagination. We search the
 * "software-development" category filtered to US locations, paginating in batches of {@value
 * #PAGE_SIZE} until all results are fetched.
 */
@Slf4j
@Component
public class AmazonScraper implements JobScraper {

    private static final String API_URL =
            "https://www.amazon.jobs/en/search.json"
                    + "?category[]=software-development&country=USA"
                    + "&offset={offset}&result_limit={limit}";
    private static final int PAGE_SIZE = 100;
    private static final DateTimeFormatter POSTED_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final WebClient webClient;

    public AmazonScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info("Amazon scraper initialized");
    }

    @Override
    public String platform() {
        return "amazon";
    }

    @Override
    public List<String> companies() {
        return List.of("amazon");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;

        try {
            while (true) {
                Map<String, Object> response =
                        webClient
                                .get()
                                .uri(API_URL, offset, PAGE_SIZE)
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();

                if (response == null) {
                    break;
                }

                int totalHits = ((Number) response.getOrDefault("hits", 0)).intValue();
                List<Map<String, Object>> jobs =
                        (List<Map<String, Object>>)
                                response.getOrDefault("jobs", Collections.emptyList());

                if (jobs.isEmpty()) {
                    break;
                }

                for (Map<String, Object> job : jobs) {
                    allJobs.add(toJobPosting(job));
                }

                offset += jobs.size();
                if (offset >= totalHits) {
                    break;
                }
            }

            log.info("Amazon: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Amazon jobs", e);
            return allJobs; // Return partial results
        }
    }

    private JobPosting toJobPosting(Map<String, Object> job) {
        String idIcims = strOrEmpty(job.get("id_icims"));
        String jobPath = strOrEmpty(job.get("job_path"));
        String description = strOrEmpty(job.get("description"));

        return JobPosting.builder()
                .company("amazon")
                .externalId(idIcims.isEmpty() ? strOrEmpty(job.get("id")) : idIcims)
                .title(strOrEmpty(job.get("title")))
                .url(jobPath.isEmpty() ? "" : "https://www.amazon.jobs" + jobPath)
                .location(strOrEmpty(job.get("normalized_location")))
                .description(stripHtml(description))
                .postedDate(parsePostedDate(strOrEmpty(job.get("posted_date"))))
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parsePostedDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            // Amazon format: "April  3, 2026" (may have extra spaces)
            String normalized = dateStr.replaceAll("\\s+", " ").trim();
            return LocalDate.parse(normalized, POSTED_DATE_FMT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
