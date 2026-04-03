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
 * Scraper for Tesla Careers ({@code tesla.com/careers/search}).
 *
 * <p>Tesla's career site blocks direct API access (403). This scraper uses Playwright to render
 * the search page and extract job listings from the DOM.
 */
@Slf4j
@Component
public class TeslaScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.tesla.com/careers/search/?query=software+engineer&country=US&page=%d";
    private static final int MAX_PAGES = 10;

    private final Browser browser;

    public TeslaScraper(Browser browser) {
        this.browser = browser;
        log.info("Tesla scraper initialized (Playwright)");
    }

    @Override
    public String platform() {
        return "tesla";
    }

    @Override
    public List<String> companies() {
        return List.of("tesla");
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
                    page.waitForSelector(
                            "a[href*='/careers/job/'], [class*='job-listing'],"
                            + " [class*='result']",
                            new Page.WaitForSelectorOptions().setTimeout(15000));
                } catch (Exception e) {
                    log.debug("Tesla page {}: no job cards found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                        "() => {\n"
                        + "  const results = [];\n"
                        + "  const links = document.querySelectorAll("
                        + "'a[href*=\"/careers/job/\"]');\n"
                        + "  const seen = new Set();\n"
                        + "  links.forEach(link => {\n"
                        + "    const href = link.getAttribute('href') || '';\n"
                        + "    const idMatch = href.match(/\\/job\\/(\\d+)/);\n"
                        + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                        + "    seen.add(idMatch[1]);\n"
                        + "    const card = link.closest('[class*=\"result\"]')"
                        + " || link.closest('li') || link.closest('div') || link;\n"
                        + "    const titleEl = card.querySelector('h2, h3, [class*=\"title\"]')"
                        + " || link;\n"
                        + "    const locEl = card.querySelector("
                        + "'[class*=\"location\"], [class*=\"meta\"] span');\n"
                        + "    results.push({\n"
                        + "      id: idMatch[1],\n"
                        + "      title: titleEl.textContent.trim(),\n"
                        + "      url: href.startsWith('http') ? href"
                        + " : 'https://www.tesla.com' + href,\n"
                        + "      location: locEl ? locEl.textContent.trim() : ''\n"
                        + "    });\n"
                        + "  });\n"
                        + "  return results;\n"
                        + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Tesla page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(JobPosting.builder()
                            .company("tesla")
                            .externalId(job.getOrDefault("id", ""))
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .description("")
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
                }

                log.debug("Tesla page {}: extracted {} jobs", pageNum, jobs.size());
            }

            log.info("Tesla: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Tesla careers", e);
            return allJobs;
        }
    }
}
