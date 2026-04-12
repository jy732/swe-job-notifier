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
 * Scraper for Microsoft Careers via the PCSX search API.
 *
 * <p>Microsoft migrated from {@code jobs.careers.microsoft.com} to {@code
 * apply.careers.microsoft.com} which exposes a public JSON search API at {@code /api/pcsx/search}.
 * This scraper calls that API directly — no browser needed.
 */
@Slf4j
@Component
public class MicrosoftScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://apply.careers.microsoft.com/api/pcsx/search"
                    + "?domain=microsoft.com"
                    + "&query=software+engineer"
                    + "&location=United+States"
                    + "&start={start}";
    private static final String JOB_URL_PREFIX = "https://apply.careers.microsoft.com";
    private static final int PAGE_SIZE = 10;
    private static final int MAX_RESULTS = 200;

    private final WebClient webClient;

    public MicrosoftScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info("Microsoft scraper initialized (PCSX API)");
    }

    @Override
    public String platform() {
        return "microsoft";
    }

    @Override
    public List<String> companies() {
        return List.of("microsoft");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates through Microsoft's PCSX search API ({@code /api/pcsx/search}) in batches of
     * {@value #PAGE_SIZE}, capped at {@value #MAX_RESULTS} total results. Searches for "software
     * engineer" filtered to United States. Stops early if a page returns fewer than {@value
     * #PAGE_SIZE} positions. On failure, returns whatever was collected so far.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();

        try {
            for (int start = 0; start < MAX_RESULTS; start += PAGE_SIZE) {
                Map<String, Object> response =
                        webClient
                                .get()
                                .uri(SEARCH_URL, start)
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();

                List<Map<String, Object>> positions = extractPositions(response);
                if (positions.isEmpty()) {
                    log.debug("Microsoft start={}: no positions returned, stopping", start);
                    break;
                }

                for (Map<String, Object> pos : positions) {
                    allJobs.add(toJobPosting(pos));
                }

                log.debug("Microsoft start={}: extracted {} jobs", start, positions.size());

                if (positions.size() < PAGE_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Failed to scrape Microsoft careers", e);
        }

        log.info("Microsoft: scraped {} total job(s)", allJobs.size());
        return allJobs;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPositions(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();
        List<Map<String, Object>> positions = (List<Map<String, Object>>) data.get("positions");
        return positions != null ? positions : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(Map<String, Object> pos) {
        String id = String.valueOf(pos.getOrDefault("displayJobId", pos.getOrDefault("id", "")));
        String title = String.valueOf(pos.getOrDefault("name", ""));
        String posUrl = String.valueOf(pos.getOrDefault("positionUrl", ""));
        String url = posUrl.startsWith("http") ? posUrl : JOB_URL_PREFIX + posUrl;

        List<String> locations =
                (List<String>) pos.getOrDefault("standardizedLocations", Collections.emptyList());
        String location = String.join("; ", locations);

        Instant postedDate = null;
        Object postedTs = pos.get("postedTs");
        if (postedTs instanceof Number num) {
            postedDate = Instant.ofEpochSecond(num.longValue());
        }

        String description = "";
        Object desc = pos.get("description");
        if (desc instanceof String descStr) {
            description = stripHtml(descStr);
        }

        return JobPosting.builder()
                .company("microsoft")
                .externalId(id)
                .title(title)
                .url(url)
                .location(location)
                .description(description)
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
