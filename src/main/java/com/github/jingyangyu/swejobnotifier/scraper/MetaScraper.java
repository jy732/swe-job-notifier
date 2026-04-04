package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Meta Careers via their GraphQL API.
 *
 * <p>Meta's career site fetches job data from {@code metacareers.com/graphql}. This scraper calls
 * the API directly using a two-step process: (1) fetch the job-search page to extract a CSRF token
 * ({@code lsd}), then (2) POST a GraphQL query with {@code doc_id=29615178951461218} to retrieve
 * all matching jobs in one request.
 */
@Slf4j
@Component
public class MetaScraper implements JobScraper {

    private static final String PAGE_URL = "https://www.metacareers.com/jobsearch";
    private static final String GRAPHQL_URL = "https://www.metacareers.com/graphql";
    private static final String DOC_ID = "29615178951461218";
    private static final Pattern LSD_PATTERN =
            Pattern.compile("\"LSD\",\\[],\\{\"token\":\"([^\"]+)\"");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/136.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public MetaScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.defaultHeader("User-Agent", USER_AGENT).build();
        this.objectMapper = objectMapper;
        log.info("Meta scraper initialized (GraphQL API)");
    }

    @Override
    public String platform() {
        return "meta";
    }

    @Override
    public List<String> companies() {
        return List.of("meta");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();

        try {
            // Step 1: Fetch the page to get the LSD (CSRF) token
            String lsd = fetchLsdToken();
            if (lsd == null) {
                log.warn("Meta: could not extract LSD token, skipping");
                return allJobs;
            }
            log.debug("Meta: obtained LSD token");

            // Step 2: Call GraphQL API
            String variables =
                    "{\"search_input\":{"
                            + "\"q\":\"software engineer\","
                            + "\"divisions\":[],\"offices\":[],\"roles\":[],"
                            + "\"leadership_levels\":[],\"saved_jobs\":[],"
                            + "\"saved_searches\":[],\"sub_teams\":[],\"teams\":[],"
                            + "\"is_leadership\":false,\"is_remote_only\":false,"
                            + "\"sort_by_new\":false,\"results_per_page\":null}}";

            String formBody =
                    "lsd="
                            + lsd
                            + "&fb_api_caller_class=RelayModern"
                            + "&fb_api_req_friendly_name=CareersJobSearchResultsDataQuery"
                            + "&variables="
                            + urlEncode(variables)
                            + "&doc_id="
                            + DOC_ID;

            String rawResponse =
                    webClient
                            .post()
                            .uri(GRAPHQL_URL)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Origin", "https://www.metacareers.com")
                            .header("Referer", "https://www.metacareers.com/jobsearch")
                            .header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("x-fb-lsd", lsd)
                            .header("Accept", "*/*")
                            .bodyValue(formBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

            if (rawResponse == null || !rawResponse.trim().startsWith("{")) {
                log.warn(
                        "Meta: non-JSON GraphQL response (length={})",
                        rawResponse != null ? rawResponse.length() : 0);
                return allJobs;
            }

            Map<String, Object> response =
                    objectMapper.readValue(
                            rawResponse, new TypeReference<Map<String, Object>>() {});

            // Check for errors
            if (response.containsKey("errors")) {
                log.warn("Meta: GraphQL errors: {}", response.get("errors"));
                return allJobs;
            }

            // Extract jobs from response
            List<Map<String, Object>> jobs = extractJobs(response);
            for (Map<String, Object> job : jobs) {
                String id = String.valueOf(job.getOrDefault("id", ""));
                String title = String.valueOf(job.getOrDefault("title", ""));

                @SuppressWarnings("unchecked")
                List<String> locations = (List<String>) job.get("locations");
                String location = locations != null ? String.join("; ", locations) : "";

                allJobs.add(
                        JobPosting.builder()
                                .company("meta")
                                .externalId(id)
                                .title(title)
                                .url("https://www.metacareers.com/profile/job_details/" + id)
                                .location(location)
                                .description("")
                                .postedDate(null)
                                .detectedAt(Instant.now())
                                .build());
            }
        } catch (Exception e) {
            log.error("Failed to scrape Meta careers", e);
        }

        log.info("Meta: scraped {} total job(s)", allJobs.size());
        return allJobs;
    }

    private String fetchLsdToken() {
        try {
            String html =
                    webClient
                            .get()
                            .uri(PAGE_URL)
                            .header("Sec-Fetch-Dest", "document")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Site", "none")
                            .header("Sec-Fetch-User", "?1")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (html == null) return null;
            Matcher m = LSD_PATTERN.matcher(html);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            log.error("Meta: failed to fetch LSD token", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractJobs(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) return Collections.emptyList();
            Map<String, Object> search =
                    (Map<String, Object>) data.get("job_search_with_featured_jobs");
            if (search == null) return Collections.emptyList();
            List<Map<String, Object>> jobs = (List<Map<String, Object>>) search.get("all_jobs");
            return jobs != null ? jobs : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Meta: unexpected response structure: {}", response.keySet());
            return Collections.emptyList();
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
