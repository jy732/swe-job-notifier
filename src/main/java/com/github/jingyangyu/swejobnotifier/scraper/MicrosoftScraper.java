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
 * Scraper for Microsoft Careers ({@code jobs.careers.microsoft.com}).
 *
 * <p>Microsoft's career site is an SPA backed by Solr/Elasticsearch. This scraper uses Playwright
 * to render search results and extract job cards from the DOM.
 */
@Slf4j
@Component
public class MicrosoftScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://jobs.careers.microsoft.com/global/en/search"
                    + "?q=software+engineer&lc=United+States&l=en_us&pg=%d&pgSz=20"
                    + "&o=Relevance&flt=true";
    private static final int MAX_PAGES = 10;

    private final Browser browser;

    public MicrosoftScraper(Browser browser) {
        this.browser = browser;
        log.info("Microsoft scraper initialized (Playwright)");
    }

    @Override
    public String platform() {
        return "microsoft";
    }

    @Override
    public List<String> companies() {
        return List.of("microsoft");
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

                try {
                    page.waitForSelector(
                            "[class*='ms-List-cell'], [class*='job-card'], "
                                    + "[data-automation-id*='job']",
                            new Page.WaitForSelectorOptions().setTimeout(15000));
                } catch (Exception e) {
                    log.debug("Microsoft page {}: no job cards found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs =
                        (List<Map<String, String>>)
                                page.evaluate(
                                        "() => {\n"
                                                + "  const results = [];\n"
                                                + "  const cards = document.querySelectorAll("
                                                + "'[class*=\"ms-List-cell\"], [class*=\"job-card\"],"
                                                + " [role=\"listitem\"]');\n"
                                                + "  cards.forEach(card => {\n"
                                                + "    const link = card.querySelector("
                                                + "'a[href*=\"/job/\"], a[href*=\"/global/en/job/\"]');\n"
                                                + "    if (!link) return;\n"
                                                + "    const href = link.getAttribute('href') || '';\n"
                                                + "    const idMatch = href.match(/\\/job\\/(\\d+)/);\n"
                                                + "    const title = link.textContent.trim()"
                                                + " || card.querySelector('h2,h3')?.textContent?.trim() || '';\n"
                                                + "    const locEl = card.querySelector("
                                                + "'[class*=\"location\"], [aria-label*=\"location\"]');\n"
                                                + "    const dateEl = card.querySelector("
                                                + "'[class*=\"date\"], [class*=\"posted\"]');\n"
                                                + "    results.push({\n"
                                                + "      id: idMatch ? idMatch[1] : href,\n"
                                                + "      title: title,\n"
                                                + "      url: href.startsWith('http') ? href"
                                                + " : 'https://jobs.careers.microsoft.com' + href,\n"
                                                + "      location: locEl ? locEl.textContent.trim() : '',\n"
                                                + "      date: dateEl ? dateEl.textContent.trim() : ''\n"
                                                + "    });\n"
                                                + "  });\n"
                                                + "  return results;\n"
                                                + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Microsoft page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(
                            JobPosting.builder()
                                    .company("microsoft")
                                    .externalId(job.getOrDefault("id", ""))
                                    .title(job.getOrDefault("title", ""))
                                    .url(job.getOrDefault("url", ""))
                                    .location(job.getOrDefault("location", ""))
                                    .description("")
                                    .postedDate(null)
                                    .detectedAt(Instant.now())
                                    .build());
                }

                log.debug("Microsoft page {}: extracted {} jobs", pageNum, jobs.size());
            }

            log.info("Microsoft: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Microsoft careers", e);
            return allJobs;
        }
    }
}
