package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Google Careers ({@code google.com/about/careers/applications/jobs/results}).
 *
 * <p>Google's career site is an SPA that loads job data dynamically via JavaScript. This scraper
 * uses Playwright to render the page and extract job listings from the DOM. It paginates by
 * incrementing the {@code page} query parameter.
 */
@Slf4j
@Component
public class GoogleScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.google.com/about/careers/applications/jobs/results"
                    + "?q=software+engineer&target_level=MID&location=United+States&page=%d";
    private static final int MAX_PAGES = 10;

    private final Browser browser;

    public GoogleScraper(Browser browser) {
        this.browser = browser;
        log.info("Google scraper initialized (Playwright)");
    }

    @Override
    public String platform() {
        return "google";
    }

    @Override
    public List<String> companies() {
        return List.of("google");
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

                // Wait for job cards to appear
                try {
                    page.waitForSelector("[class*='job-results-list'] li, [data-job-id]",
                            new Page.WaitForSelectorOptions().setTimeout(10000));
                } catch (Exception e) {
                    log.debug("Google page {}: no job cards found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                        "() => {\n"
                        + "  const results = [];\n"
                        + "  const cards = document.querySelectorAll("
                        + "'li[class*=\"lLd3Je\"], [data-job-id], "
                        + "[class*=\"job-results\"] li');\n"
                        + "  cards.forEach(card => {\n"
                        + "    const link = card.querySelector('a[href*=\"/jobs/results/\"]')"
                        + " || card.querySelector('a');\n"
                        + "    const title = card.querySelector('h3')"
                        + " || card.querySelector('[class*=\"title\"]');\n"
                        + "    const location = card.querySelector('[class*=\"location\"]')"
                        + " || card.querySelector('span');\n"
                        + "    if (title && link) {\n"
                        + "      const href = link.getAttribute('href') || '';\n"
                        + "      const idMatch = href.match(/\\/(\\d+)\\/?/);\n"
                        + "      results.push({\n"
                        + "        id: idMatch ? idMatch[1] : href,\n"
                        + "        title: title.textContent.trim(),\n"
                        + "        url: href.startsWith('http') ? href"
                        + " : 'https://www.google.com' + href,\n"
                        + "        location: location ? location.textContent.trim() : ''\n"
                        + "      });\n"
                        + "    }\n"
                        + "  });\n"
                        + "  return results;\n"
                        + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Google page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(JobPosting.builder()
                            .company("google")
                            .externalId(job.getOrDefault("id", ""))
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .description("")
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
                }

                log.debug("Google page {}: extracted {} jobs", pageNum, jobs.size());
            }

            log.info("Google: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Google careers", e);
            return allJobs;
        }
    }
}
