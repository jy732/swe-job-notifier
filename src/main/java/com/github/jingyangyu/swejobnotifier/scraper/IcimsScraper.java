package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.config.IcimsProperties;
import com.github.jingyangyu.swejobnotifier.config.IcimsProperties.IcimsCompany;
import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for companies using iCIMS career portals. Fetches search results from the iCIMS career
 * portal HTML endpoint and extracts job data using regex patterns.
 */
@Slf4j
@Component
public class IcimsScraper implements JobScraper {

    private static final int PAGE_SIZE = 25;

    // Matches job links: href="/jobs/{id}/job" or href="/jobs/{id}/whatever"
    private static final Pattern JOB_LINK_PATTERN =
            Pattern.compile(
                    "<a[^>]*href=\"/jobs/(\\d+)/[^\"]*\"[^>]*class=\"[^\"]*iCIMS_Anchor[^\"]*\"[^>]*>"
                            + "([^<]+)</a>");

    // Matches total results count from paginator text
    private static final Pattern TOTAL_PATTERN =
            Pattern.compile("of\\s+(\\d[\\d,]*)\\s+results", Pattern.CASE_INSENSITIVE);

    // Matches location text within an iCIMS table row
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile(
                    "class=\"[^\"]*header cell[^\"]*\"[^>]*>\\s*Location",
                    Pattern.CASE_INSENSITIVE);

    private final WebClient webClient;
    private final IcimsProperties properties;

    public IcimsScraper(WebClient.Builder webClientBuilder, IcimsProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "iCIMS scraper initialized with {} company(ies)", properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "icims";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(IcimsCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<IcimsCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No iCIMS config found for company: {}", company);
            return Collections.emptyList();
        }

        IcimsCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;

        try {
            while (offset < total) {
                String html = fetchSearchPage(config, offset);
                if (html == null || html.isBlank()) {
                    break;
                }

                // Parse total results on the first page
                if (offset == 0) {
                    total = parseTotal(html);
                    if (total == 0) {
                        break;
                    }
                }

                List<JobPosting> pageJobs = parseJobsFromHtml(company, config, html);
                if (pageJobs.isEmpty()) {
                    break;
                }

                allJobs.addAll(pageJobs);
                offset += PAGE_SIZE;
            }

            log.info("iCIMS [{}]: scraped {} total job(s)", company, allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape iCIMS for company: {}", company, e);
            return allJobs;
        }
    }

    private String fetchSearchPage(IcimsCompany config, int offset) {
        return webClient
                .get()
                .uri(config.searchUrl(PAGE_SIZE, offset))
                .header(
                        "User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko)")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private int parseTotal(String html) {
        Matcher m = TOTAL_PATTERN.matcher(html);
        if (m.find()) {
            return Integer.parseInt(m.group(1).replace(",", ""));
        }
        // If no paginator, count the job links on this page as the total
        Matcher jobMatcher = JOB_LINK_PATTERN.matcher(html);
        int count = 0;
        while (jobMatcher.find()) count++;
        return count;
    }

    private List<JobPosting> parseJobsFromHtml(String company, IcimsCompany config, String html) {
        List<JobPosting> jobs = new ArrayList<>();
        Matcher m = JOB_LINK_PATTERN.matcher(html);

        while (m.find()) {
            String jobId = m.group(1);
            String title = m.group(2).trim();

            jobs.add(
                    JobPosting.builder()
                            .company(company)
                            .externalId(jobId)
                            .title(stripHtml(title))
                            .url(config.jobUrl(jobId))
                            .location("")
                            .description("")
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
        }

        return jobs;
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
