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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Apple Jobs ({@code jobs.apple.com}).
 *
 * <p>Apple's career site is a React SPA with server-side hydration. Job data is embedded
 * in {@code __staticRouterHydrationData}. This scraper navigates the search pages and
 * extracts job cards from the rendered DOM.
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
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));

                try {
                    page.waitForSelector("table tbody tr, [class*='table-row']",
                            new Page.WaitForSelectorOptions().setTimeout(10000));
                } catch (Exception e) {
                    log.debug("Apple page {}: no job rows found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                        "() => {\n"
                        + "  const results = [];\n"
                        + "  const rows = document.querySelectorAll("
                        + "'table tbody tr, [class*=\"table\"] [class*=\"row\"]');\n"
                        + "  rows.forEach(row => {\n"
                        + "    const link = row.querySelector('a[href*=\"/details/\"]');\n"
                        + "    if (!link) return;\n"
                        + "    const href = link.getAttribute('href') || '';\n"
                        + "    const idMatch = href.match(/\\/details\\/(\\d[\\d-]+)/);\n"
                        + "    const cells = row.querySelectorAll('td');\n"
                        + "    const dateEl = row.querySelector("
                        + "'[class*=\"date\"], td:last-child');\n"
                        + "    results.push({\n"
                        + "      id: idMatch ? idMatch[1] : href,\n"
                        + "      title: link.textContent.trim(),\n"
                        + "      url: 'https://jobs.apple.com' + href,\n"
                        + "      location: cells.length > 2"
                        + " ? cells[2].textContent.trim() : '',\n"
                        + "      date: dateEl ? dateEl.textContent.trim() : ''\n"
                        + "    });\n"
                        + "  });\n"
                        + "  return results;\n"
                        + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Apple page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(JobPosting.builder()
                            .company("apple")
                            .externalId(job.getOrDefault("id", ""))
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .description("")
                            .postedDate(parseDate(job.getOrDefault("date", "")))
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
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
