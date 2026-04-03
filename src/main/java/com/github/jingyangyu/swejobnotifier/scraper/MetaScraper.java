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
 * Scraper for Meta Careers ({@code metacareers.com}).
 *
 * <p>Meta's career site uses a GraphQL/Relay-based SPA. This scraper uses Playwright to render
 * the search results and extract job cards from the DOM.
 */
@Slf4j
@Component
public class MetaScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.metacareers.com/jobs"
                    + "?q=software+engineer&teams[0]=Engineering"
                    + "&locations[0]=United+States&page=%d";
    private static final int MAX_PAGES = 10;

    private final Browser browser;

    public MetaScraper(Browser browser) {
        this.browser = browser;
        log.info("Meta scraper initialized (Playwright)");
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

        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
                String url = String.format(SEARCH_URL, pageNum);
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));

                try {
                    page.waitForSelector(
                            "a[href*='/jobs/'], [class*='job'], [role='listitem']",
                            new Page.WaitForSelectorOptions().setTimeout(15000));
                } catch (Exception e) {
                    log.debug("Meta page {}: no job cards found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                        "() => {\n"
                        + "  const results = [];\n"
                        + "  const links = document.querySelectorAll("
                        + "'a[href*=\"/jobs/\"][href*=\"/\"]');\n"
                        + "  const seen = new Set();\n"
                        + "  links.forEach(link => {\n"
                        + "    const href = link.getAttribute('href') || '';\n"
                        + "    const idMatch = href.match(/\\/jobs\\/(\\d+)/);\n"
                        + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                        + "    seen.add(idMatch[1]);\n"
                        + "    const card = link.closest('[role=\"listitem\"]')"
                        + " || link.closest('div') || link;\n"
                        + "    const titleEl = card.querySelector('div[dir=\"auto\"]')"
                        + " || link;\n"
                        + "    const locEl = card.querySelector("
                        + "'[class*=\"location\"], span');\n"
                        + "    results.push({\n"
                        + "      id: idMatch[1],\n"
                        + "      title: titleEl.textContent.trim(),\n"
                        + "      url: href.startsWith('http') ? href"
                        + " : 'https://www.metacareers.com' + href,\n"
                        + "      location: locEl && locEl !== titleEl"
                        + " ? locEl.textContent.trim() : ''\n"
                        + "    });\n"
                        + "  });\n"
                        + "  return results;\n"
                        + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Meta page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                for (Map<String, String> job : jobs) {
                    allJobs.add(JobPosting.builder()
                            .company("meta")
                            .externalId(job.getOrDefault("id", ""))
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .description("")
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
                }

                log.debug("Meta page {}: extracted {} jobs", pageNum, jobs.size());
            }

            log.info("Meta: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Meta careers", e);
            return allJobs;
        }
    }
}
