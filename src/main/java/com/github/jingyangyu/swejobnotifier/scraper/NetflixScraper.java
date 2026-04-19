package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Netflix's career site, which uses Eightfold AI as its ATS.
 *
 * <p>Netflix migrated from Lever to Eightfold AI in 2025. The Eightfold SmartApply API provides a
 * JSON listing endpoint with pagination and a per-job detail endpoint for full descriptions.
 *
 * <ul>
 *   <li>List: {@code GET /api/apply/v2/jobs?domain=netflix.com&start=0&num=100}
 *   <li>Detail: {@code GET /api/apply/v2/jobs/{id}?domain=netflix.com}
 * </ul>
 *
 * <p>The list endpoint returns metadata only (no descriptions), so description fetching is deferred
 * to {@link #fetchDescriptions(List)} which visits the detail endpoint for each unseen job.
 */
@Slf4j
@Component
public class NetflixScraper implements JobScraper {

    private static final String BASE_URL = "https://explore.jobs.netflix.net";
    private static final String LIST_URL =
            BASE_URL + "/api/apply/v2/jobs?domain=netflix.com&start={start}&num={num}&hl=en";
    private static final String DETAIL_URL =
            BASE_URL + "/api/apply/v2/jobs/{id}?domain=netflix.com&hl=en";
    private static final int PAGE_SIZE = 100;

    private final WebClient webClient;

    public NetflixScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info("Netflix scraper initialized (Eightfold AI)");
    }

    @Override
    public String platform() {
        return "netflix";
    }

    @Override
    public List<String> companies() {
        return List.of("netflix");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates through the Eightfold SmartApply API in batches of {@value #PAGE_SIZE}. Extracts
     * job metadata from the {@code positions} array. Stops when positions returned is less than the
     * page size. Descriptions are not included in the list response — see {@link
     * #fetchDescriptions(List)}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();
        int start = 0;

        try {
            while (true) {
                Map<String, Object> response =
                        webClient
                                .get()
                                .uri(LIST_URL, start, PAGE_SIZE)
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();

                if (response == null) break;

                List<Map<String, Object>> positions =
                        (List<Map<String, Object>>)
                                response.getOrDefault("positions", Collections.emptyList());

                for (Map<String, Object> pos : positions) {
                    allJobs.add(toJobPosting(pos));
                }

                if (positions.size() < PAGE_SIZE) break;
                start += PAGE_SIZE;
            }

            log.info("Netflix: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Netflix careers", e);
            return allJobs;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches full job descriptions from the Eightfold per-job detail endpoint. The list
     * response omits descriptions, so this is called post-dedup for unseen jobs only. HTML tags are
     * stripped from the description content.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void fetchDescriptions(List<JobPosting> jobs) {
        if (jobs.isEmpty()) return;
        log.info("Netflix: fetching descriptions for {} unseen job(s)", jobs.size());

        for (JobPosting job : jobs) {
            try {
                Map<String, Object> response =
                        webClient
                                .get()
                                .uri(DETAIL_URL, job.getExternalId())
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();

                if (response == null) continue;

                Map<String, Object> position =
                        (Map<String, Object>) response.getOrDefault("position", response);
                String desc = strOrEmpty(position.get("job_description"));
                if (!desc.isEmpty()) {
                    job.setDescription(stripHtml(desc));
                }
            } catch (Exception e) {
                log.warn("Netflix: failed to fetch description for job {}", job.getExternalId(), e);
            }
        }
    }

    private JobPosting toJobPosting(Map<String, Object> pos) {
        String id = String.valueOf(pos.getOrDefault("id", ""));
        String url = strOrEmpty(pos.get("canonicalPositionUrl"));
        if (url.isEmpty()) {
            url = BASE_URL + "/careers/job/" + id;
        }

        Instant postedDate = null;
        Object tCreate = pos.get("t_create");
        if (tCreate instanceof Number n) {
            postedDate = Instant.ofEpochSecond(n.longValue());
        }

        return JobPosting.builder()
                .company("netflix")
                .externalId(id)
                .title(strOrEmpty(pos.get("name")))
                .url(url)
                .location(strOrEmpty(pos.get("location")))
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
