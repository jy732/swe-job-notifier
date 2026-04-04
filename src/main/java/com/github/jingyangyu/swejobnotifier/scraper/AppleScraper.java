package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Apple Jobs ({@code jobs.apple.com}).
 *
 * <p>Apple's career site is a React SPA that embeds job data in {@code
 * window.__staticRouterHydrationData}. This scraper extracts jobs directly from that embedded JSON
 * rather than querying the DOM, making it resilient to CSS class changes.
 */
@Slf4j
@Component
public class AppleScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://jobs.apple.com/en-us/search"
                    + "?search=software+engineer&sort=newest"
                    + "&location=united-states-USA&page=%d";
    private static final int MAX_PAGES = 10;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US);

    private final Browser browser;

    public AppleScraper(Browser browser) {
        this.browser = browser;
        log.info("Apple scraper initialized (Playwright)");
    }

    @Override
    public String platform() {
        return "apple";
    }

    @Override
    public List<String> companies() {
        return List.of("apple");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
                String url = String.format(SEARCH_URL, pageNum);
                page.navigate(
                        url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

                // Extract jobs from embedded __staticRouterHydrationData JSON.
                // First try hydration data, then fall back to DOM scraping.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> jobs =
                        (List<Map<String, Object>>)
                                page.evaluate(
                                        "() => {\n"
                                                + "  // Helper: recursively find arrays that look like job results\n"
                                                + "  function findJobArrays(obj, depth) {\n"
                                                + "    if (depth > 5 || !obj) return null;\n"
                                                + "    if (Array.isArray(obj) && obj.length > 0 && obj[0]"
                                                + " && (obj[0].postingTitle || obj[0].jobTitle"
                                                + " || obj[0].transformedPostingTitle)) return obj;\n"
                                                + "    if (typeof obj === 'object') {\n"
                                                + "      for (const v of Object.values(obj)) {\n"
                                                + "        const found = findJobArrays(v, depth + 1);\n"
                                                + "        if (found) return found;\n"
                                                + "      }\n"
                                                + "    }\n"
                                                + "    return null;\n"
                                                + "  }\n"
                                                + "  try {\n"
                                                + "    const data = window.__staticRouterHydrationData;\n"
                                                + "    if (!data || !data.loaderData) return [];\n"
                                                + "    const arr = findJobArrays(data.loaderData, 0);\n"
                                                + "    if (!arr) return [];\n"
                                                + "    return arr.map(j => {\n"
                                                + "      const keys = Object.keys(j);\n"
                                                + "      // locations is an array of objects with 'name' field\n"
                                                + "      let loc = '';\n"
                                                + "      if (Array.isArray(j.locations) && j.locations.length > 0) {\n"
                                                + "        loc = j.locations.map(l => l.name || l).join('; ');\n"
                                                + "      } else if (typeof j.locations === 'string') {\n"
                                                + "        loc = j.locations;\n"
                                                + "      }\n"
                                                + "      const pid = j.positionId || j.reqId || '';\n"
                                                + "      return {\n"
                                                + "        id: String(pid),\n"
                                                + "        title: j.postingTitle || j.transformedPostingTitle || '',\n"
                                                + "        url: '/en-us/details/' + pid,\n"
                                                + "        location: loc,\n"
                                                + "        date: j.postingDate || j.postDateInGMT || '',\n"
                                                + "        _keys: keys.join(',')\n"
                                                + "      };\n"
                                                + "    });\n"
                                                + "  } catch (e) {\n"
                                                + "    return [];\n"
                                                + "  }\n"
                                                + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Apple page {}: no jobs found in hydration data, stopping", pageNum);
                    break;
                }

                for (Map<String, Object> job : jobs) {
                    String jobUrl = String.valueOf(job.getOrDefault("url", ""));
                    if (!jobUrl.startsWith("http")) {
                        jobUrl = "https://jobs.apple.com" + jobUrl;
                    }
                    allJobs.add(
                            JobPosting.builder()
                                    .company("apple")
                                    .externalId(String.valueOf(job.getOrDefault("id", "")))
                                    .title(String.valueOf(job.getOrDefault("title", "")))
                                    .url(jobUrl)
                                    .location(String.valueOf(job.getOrDefault("location", "")))
                                    .description("")
                                    .postedDate(
                                            parseDate(String.valueOf(job.getOrDefault("date", ""))))
                                    .detectedAt(Instant.now())
                                    .build());
                }

                log.debug("Apple page {}: extracted {} jobs", pageNum, jobs.size());
            }

            log.info("Apple: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Apple jobs", e);
            return allJobs;
        }
    }

    private static Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FMT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
