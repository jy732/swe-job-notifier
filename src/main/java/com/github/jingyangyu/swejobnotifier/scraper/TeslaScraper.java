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
 * Scraper for Tesla Careers ({@code tesla.com/careers/search}).
 *
 * <p>Tesla uses Akamai bot detection that frequently blocks headless browsers. This scraper
 * attempts to load the page and gracefully returns 0 results when blocked rather than
 * throwing errors.
 */
@Slf4j
@Component
public class TeslaScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.tesla.com/careers/search/?query=software+engineer&country=US";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

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

        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setViewportSize(1920, 1080))) {
            Page page = context.newPage();

            page.navigate(SEARCH_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE)
                    .setTimeout(30000));

            // Check if blocked by WAF
            String bodyStart = (String) page.evaluate(
                    "() => document.body?.innerText?.substring(0, 200) || ''");
            if (bodyStart.contains("Access Denied")) {
                log.debug("Tesla: blocked by WAF, skipping");
                log.info("Tesla: scraped 0 total job(s)");
                return allJobs;
            }

            // Wait for job links
            try {
                page.waitForSelector("a[href*='/careers/job/']",
                        new Page.WaitForSelectorOptions().setTimeout(15000));
            } catch (Exception e) {
                log.debug("Tesla: no job links found");
                log.info("Tesla: scraped 0 total job(s)");
                return allJobs;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                    "() => {\n"
                    + "  const results = [];\n"
                    + "  const seen = new Set();\n"
                    + "  const links = document.querySelectorAll("
                    + "\"a[href*='/careers/job/']\");\n"
                    + "  links.forEach(link => {\n"
                    + "    const href = link.getAttribute('href') || '';\n"
                    + "    const idMatch = href.match(/\\/job\\/(\\d+)/);\n"
                    + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                    + "    seen.add(idMatch[1]);\n"
                    + "    const card = link.closest('li')"
                    + " || link.closest('div') || link;\n"
                    + "    const titleEl = card.querySelector("
                    + "'h2, h3') || link;\n"
                    + "    const title = titleEl.textContent.trim();\n"
                    + "    if (!title || title.length < 3) return;\n"
                    + "    let location = '';\n"
                    + "    const spans = card.querySelectorAll('span');\n"
                    + "    for (const s of spans) {\n"
                    + "      const text = s.textContent.trim();\n"
                    + "      if (text && text !== title && text.length < 100) {\n"
                    + "        location = text; break;\n"
                    + "      }\n"
                    + "    }\n"
                    + "    results.push({\n"
                    + "      id: idMatch[1],\n"
                    + "      title: title,\n"
                    + "      url: href.startsWith('http') ? href"
                    + " : 'https://www.tesla.com' + href,\n"
                    + "      location: location\n"
                    + "    });\n"
                    + "  });\n"
                    + "  return results;\n"
                    + "}");

            if (jobs != null) {
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
            }
        } catch (Exception e) {
            log.error("Failed to scrape Tesla careers", e);
        }

        log.info("Tesla: scraped {} total job(s)", allJobs.size());
        return allJobs;
    }
}
