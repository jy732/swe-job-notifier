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
 * Scraper for TikTok Careers ({@code lifeattiktok.com/search}). TikTok uses a Next.js SPA backed by
 * an API that requires browser-level request headers, so we use Playwright to render the page and
 * extract job data from the DOM.
 */
@Slf4j
@Component
public class TikTokScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://lifeattiktok.com/search?keyword=software+engineer";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private final Browser browser;

    public TikTokScraper(Browser browser) {
        this.browser = browser;
        log.info("TikTok scraper initialized (Playwright)");
    }

    @Override
    public String platform() {
        return "tiktok";
    }

    @Override
    public List<String> companies() {
        return List.of("tiktok");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        List<JobPosting> allJobs = new ArrayList<>();

        try (BrowserContext context =
                browser.newContext(
                        new Browser.NewContextOptions()
                                .setUserAgent(USER_AGENT)
                                .setViewportSize(1920, 1080))) {
            Page page = context.newPage();

            page.navigate(
                    SEARCH_URL,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(30000));

            // Wait for job card links to render
            try {
                page.waitForSelector(
                        "a[href*='/search/']", new Page.WaitForSelectorOptions().setTimeout(15000));
            } catch (Exception e) {
                log.debug("TikTok: no job links found after waiting");
                log.info("TikTok: scraped 0 total job(s)");
                return allJobs;
            }

            // Scroll down to load more results
            for (int i = 0; i < 5; i++) {
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                page.waitForTimeout(1500);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> jobs =
                    (List<Map<String, String>>)
                            page.evaluate(
                                    "() => {\n"
                                            + "  const results = [];\n"
                                            + "  const seen = new Set();\n"
                                            + "  const links = document.querySelectorAll("
                                            + "\"a[href*='/search/']\");\n"
                                            + "  links.forEach(link => {\n"
                                            + "    const href = link.getAttribute('href') || '';\n"
                                            + "    const idMatch = href.match("
                                            + "/\\/search\\/(\\d+)/);\n"
                                            + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                                            + "    seen.add(idMatch[1]);\n"
                                            + "    const text = link.textContent.trim();\n"
                                            + "    if (!text || text.length < 5) return;\n"
                                            + "    const parts = text.split('\\n')"
                                            + ".map(s => s.trim()).filter(Boolean);\n"
                                            + "    const title = parts[0] || text;\n"
                                            + "    const location = parts[1] || '';\n"
                                            + "    results.push({\n"
                                            + "      id: idMatch[1],\n"
                                            + "      title: title,\n"
                                            + "      url: 'https://lifeattiktok.com/search/'"
                                            + " + idMatch[1],\n"
                                            + "      location: location\n"
                                            + "    });\n"
                                            + "  });\n"
                                            + "  return results;\n"
                                            + "}");

            if (jobs != null) {
                for (Map<String, String> job : jobs) {
                    allJobs.add(
                            JobPosting.builder()
                                    .company("tiktok")
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
            log.error("Failed to scrape TikTok careers", e);
        }

        log.info("TikTok: scraped {} total job(s)", allJobs.size());
        return allJobs;
    }
}
