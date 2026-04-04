package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Google Careers ({@code google.com/about/careers/applications/jobs/results}).
 *
 * <p>Google's career site is an SPA that loads job data via JavaScript. This scraper uses
 * Playwright to render the page, then extracts job data using structural selectors (links to job
 * detail pages with numeric IDs) rather than class-name-dependent selectors.
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
                page.navigate(
                        url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

                // Wait for job detail links (relative paths without leading slash)
                try {
                    page.waitForSelector(
                            "a[href*='jobs/results/']",
                            new Page.WaitForSelectorOptions().setTimeout(15000));
                } catch (Exception e) {
                    log.debug("Google page {}: no job links found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs =
                        (List<Map<String, String>>)
                                page.evaluate(
                                        "() => {\n"
                                                + "  const results = [];\n"
                                                + "  const seen = new Set();\n"
                                                + "  const links = document.querySelectorAll("
                                                + "\"a[href*='jobs/results/']\");\n"
                                                + "  links.forEach(link => {\n"
                                                + "    const href = link.getAttribute('href') || '';\n"
                                                + "    const idMatch = href.match(/results\\/(\\d+)/);\n"
                                                + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                                                + "    seen.add(idMatch[1]);\n"
                                                + "    const card = link.closest('li') || link.closest('[role]')"
                                                + " || link.parentElement;\n"
                                                + "    const titleEl = card ? card.querySelector('h3') : null;\n"
                                                + "    const title = titleEl ? titleEl.textContent.trim()"
                                                + " : link.textContent.trim();\n"
                                                + "    let location = '';\n"
                                                + "    if (card) {\n"
                                                + "      const spans = card.querySelectorAll('span');\n"
                                                + "      for (const s of spans) {\n"
                                                + "        const text = s.textContent.trim()"
                                                + ".replace(/^place/, '');\n"
                                                + "        if (text && text !== title && text.includes(',')) {\n"
                                                + "          location = text; break;\n"
                                                + "        }\n"
                                                + "      }\n"
                                                + "    }\n"
                                                + "    // Fix relative URL: ensure path separator\n"
                                                + "    let fullUrl = href.startsWith('http') ? href"
                                                + " : href.startsWith('/') ? 'https://www.google.com' + href"
                                                + " : 'https://www.google.com/about/careers/applications/' + href;\n"
                                                + "    // Strip query params for cleaner dedup\n"
                                                + "    fullUrl = fullUrl.split('?')[0];\n"
                                                + "    results.push({\n"
                                                + "      id: idMatch[1],\n"
                                                + "      title: title,\n"
                                                + "      url: fullUrl,\n"
                                                + "      location: location\n"
                                                + "    });\n"
                                                + "  });\n"
                                                + "  return results;\n"
                                                + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Google page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(
                            JobPosting.builder()
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
