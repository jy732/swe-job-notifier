package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Two Sigma's career site, which uses Avature ATS with server-rendered HTML.
 *
 * <p>Unlike JSON API-based scrapers, this scraper fetches HTML pages and extracts job data via
 * regex. The listing page at {@code careers.twosigma.com/careers/OpenRoles} is paginated with 10
 * jobs per page. Description fetching is deferred to {@link #fetchDescriptions(List)} since each
 * detail page requires a separate HTTP request.
 */
@Slf4j
@Component
public class TwoSigmaScraper implements JobScraper {

    private static final String BASE_URL = "https://careers.twosigma.com";
    private static final String LISTING_URL = BASE_URL + "/careers/OpenRoles";
    private static final int PAGE_SIZE = 10;
    private static final int MAX_PAGES = 10;

    /**
     * Matches JobDetail URLs in href attributes. Captures the full path segment and trailing job
     * ID. Each job appears 3 times per listing (title link + 2 "View role" buttons), so results are
     * deduplicated.
     */
    private static final Pattern JOB_URL_PATTERN =
            Pattern.compile(
                    "href=\"(https://careers\\.twosigma\\.com/careers/JobDetail/([^\"]+)/(\\d+))\"");

    /**
     * Matches job titles inside the {@code <a class="link">} tags within {@code <h3>} headings.
     * Captures the trimmed title text between the anchor tags.
     */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile(
                    "<a class=\"link\" href=\"[^\"]*JobDetail[^\"]*\">[\\s]*(.*?)[\\s]*</a>",
                    Pattern.DOTALL);

    /**
     * Matches location text inside {@code <span class="paragraph_inner-span">} elements. The first
     * match after each job heading contains the location (e.g. "United States - NY New York").
     */
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile("<span class=\"paragraph_inner-span\">([^<]+)</span>");

    /**
     * Matches the job description content inside the detail page's {@code
     * article__content__view__field__value} div. Uses a non-greedy match to capture HTML content
     * which is then stripped of tags.
     */
    private static final Pattern DESCRIPTION_PATTERN =
            Pattern.compile(
                    "<div class=\"article__content__view__field__value\">(.*?)</div>",
                    Pattern.DOTALL);

    private final WebClient webClient;

    public TwoSigmaScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info("Two Sigma scraper initialized");
    }

    @Override
    public String platform() {
        return "twosigma";
    }

    @Override
    public List<String> companies() {
        return List.of("twosigma");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates through the Two Sigma OpenRoles page in batches of {@value #PAGE_SIZE}. Extracts
     * job URLs, titles, and locations from server-rendered HTML via regex. Stops when a page
     * returns fewer URLs than expected or after {@value #MAX_PAGES} pages. Descriptions are not
     * fetched here — see {@link #fetchDescriptions(List)}.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                int offset = page * PAGE_SIZE;
                String url =
                        String.format(
                                "%s?jobRecordsPerPage=%d&jobOffset=%d",
                                LISTING_URL, PAGE_SIZE, offset);
                String html = fetchHtml(url);
                if (html == null || html.isEmpty()) break;

                List<JobPosting> pageJobs = parseListingPage(html, seenIds);
                if (pageJobs.isEmpty()) break;

                allJobs.addAll(pageJobs);
                if (pageJobs.size() < PAGE_SIZE) break;
            }

            log.info("Two Sigma: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Two Sigma careers", e);
            return allJobs;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches full job descriptions by visiting each job's detail page via HTTP. Extracts
     * description text from the {@code article__content__view__field__value} div elements and
     * concatenates all sections. HTML tags are stripped from the content.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        if (jobs.isEmpty()) return;
        log.info("Two Sigma: fetching descriptions for {} unseen job(s)", jobs.size());

        for (JobPosting job : jobs) {
            try {
                String html = fetchHtml(job.getUrl());
                if (html == null) continue;

                StringBuilder desc = new StringBuilder();
                Matcher m = DESCRIPTION_PATTERN.matcher(html);
                while (m.find()) {
                    String section = stripHtml(m.group(1));
                    if (!section.isBlank()) {
                        if (!desc.isEmpty()) desc.append(" ");
                        desc.append(section);
                    }
                }
                if (!desc.isEmpty()) {
                    job.setDescription(desc.toString());
                }
            } catch (Exception e) {
                log.warn("Two Sigma: failed to fetch description for {}", job.getUrl(), e);
            }
        }
    }

    /**
     * Parses a single listing page's HTML to extract job postings. Deduplicates by job ID since
     * each job URL appears 3 times per listing (title link + mobile/desktop "View role" buttons).
     */
    private List<JobPosting> parseListingPage(String html, Set<String> seenIds) {
        List<JobPosting> jobs = new ArrayList<>();

        // Extract all unique job URLs with IDs
        List<String[]> uniqueJobs = new ArrayList<>();
        Matcher urlMatcher = JOB_URL_PATTERN.matcher(html);
        while (urlMatcher.find()) {
            String jobUrl = urlMatcher.group(1);
            String jobId = urlMatcher.group(3);
            if (seenIds.add(jobId)) {
                uniqueJobs.add(new String[] {jobUrl, jobId});
            }
        }

        // Extract titles (one per job, in <h3> -> <a class="link">)
        List<String> titles = new ArrayList<>();
        Matcher titleMatcher = TITLE_PATTERN.matcher(html);
        while (titleMatcher.find()) {
            titles.add(titleMatcher.group(1).trim());
        }

        // Extract locations (first <span class="paragraph_inner-span"> after each title)
        // Locations follow a pattern: each job has location span(s) after the title
        List<String> locations = extractLocations(html);

        for (int i = 0; i < uniqueJobs.size(); i++) {
            String jobUrl = uniqueJobs.get(i)[0];
            String jobId = uniqueJobs.get(i)[1];
            String title = i < titles.size() ? titles.get(i) : "";
            String location = i < locations.size() ? locations.get(i) : "";

            jobs.add(
                    JobPosting.builder()
                            .company("twosigma")
                            .externalId(jobId)
                            .title(title)
                            .url(jobUrl)
                            .location(location)
                            .detectedAt(Instant.now())
                            .build());
        }

        return jobs;
    }

    /**
     * Extracts locations from the listing page HTML. Each job's location is the first {@code
     * paragraph_inner-span} that appears within an {@code article__header__content__text} div.
     */
    private List<String> extractLocations(String html) {
        List<String> locations = new ArrayList<>();
        Pattern blockPattern =
                Pattern.compile(
                        "<div class=\"article__header__content__text\">(.*?)</div>\\s*<div",
                        Pattern.DOTALL);
        Matcher blockMatcher = blockPattern.matcher(html);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            Matcher locMatcher = LOCATION_PATTERN.matcher(block);
            if (locMatcher.find()) {
                locations.add(locMatcher.group(1).trim());
            }
        }
        return locations;
    }

    /** Fetches a URL and returns the response body as a string. */
    private String fetchHtml(String url) {
        return webClient
                .get()
                .uri(url)
                .header(
                        "User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Safari/537.36")
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
