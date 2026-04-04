package com.github.jingyangyu.swejobnotifier.controller;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.scraper.JobScraper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test endpoint for triggering scrapers on demand without waiting for the poll cycle.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code POST /api/test/scrape/google/google} — scrape Google
 *   <li>{@code POST /api/test/scrape/greenhouse/stripe} — scrape Stripe via Greenhouse
 *   <li>{@code POST /api/test/scrape-all} — scrape all platforms, return summary
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class ScrapeTestController {

    private final List<JobScraper> scrapers;
    private final Browser browser;

    public ScrapeTestController(List<JobScraper> scrapers, Browser browser) {
        this.scrapers = scrapers;
        this.browser = browser;
    }

    @PostMapping("/scrape/{platform}/{company}")
    public Map<String, Object> scrapeSingle(
            @PathVariable String platform, @PathVariable String company) {
        JobScraper scraper = scrapers.stream()
                .filter(s -> s.platform().equalsIgnoreCase(platform))
                .findFirst()
                .orElse(null);

        if (scraper == null) {
            return Map.of("error", "Unknown platform: " + platform,
                    "available", scrapers.stream().map(JobScraper::platform).toList());
        }

        log.info("Test scrape: platform={}, company={}", platform, company);
        long start = System.currentTimeMillis();
        List<JobPosting> jobs = scraper.scrape(company);
        long elapsed = System.currentTimeMillis() - start;

        List<Map<String, String>> sample = jobs.stream()
                .limit(5)
                .map(j -> Map.of(
                        "title", j.getTitle(),
                        "location", j.getLocation() != null ? j.getLocation() : "",
                        "url", j.getUrl() != null ? j.getUrl() : ""))
                .toList();

        return Map.of(
                "platform", platform,
                "company", company,
                "count", jobs.size(),
                "elapsedMs", elapsed,
                "sample", sample);
    }

    /**
     * Debug endpoint: navigates to a URL with Playwright and dumps links + body text.
     * Usage: POST /api/test/debug-page?url=https://...
     */
    @PostMapping("/debug-page")
    public Map<String, Object> debugPage(@RequestParam String url) {
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/136.0.0.0 Safari/537.36")
                        .setViewportSize(1920, 1080))) {
            Page page = context.newPage();

            // Capture API/XHR requests during page load (with POST bodies + headers)
            List<String> apiRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
            page.onRequest(request -> {
                String reqUrl = request.url();
                if (reqUrl.contains("graphql")) {
                    String postData = request.postData() != null
                            ? request.postData().substring(0,
                                    Math.min(request.postData().length(), 2000))
                            : "";
                    Map<String, String> headers = request.headers();
                    StringBuilder sb = new StringBuilder();
                    sb.append(request.method()).append(" ").append(reqUrl).append("\n");
                    headers.forEach((k, v) -> {
                        if (k.startsWith("x-") || k.equals("content-type")
                                || k.equals("cookie")) {
                            sb.append("  ").append(k).append(": ").append(
                                    v.substring(0, Math.min(v.length(), 200))).append("\n");
                        }
                    });
                    sb.append("  BODY: ").append(postData);
                    apiRequests.add(sb.toString());
                } else if (reqUrl.contains("api") || reqUrl.contains("search")
                        || reqUrl.contains("job")) {
                    apiRequests.add(request.method() + " " + reqUrl);
                }
            });

            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(30000));
            page.waitForTimeout(3000);

            String finalUrl = page.url();
            String title = page.title();

            @SuppressWarnings("unchecked")
            List<String> links = (List<String>) page.evaluate(
                    "() => Array.from(document.querySelectorAll('a[href]'))"
                    + ".map(a => a.getAttribute('href') + ' [' "
                    + "+ (a.textContent||'').trim().substring(0,50) + ']')"
                    + ".filter(h => h && (h.includes('job') || h.includes('career')"
                    + " || h.includes('result') || h.includes('position')))"
                    + ".slice(0, 40)");

            String bodyText = (String) page.evaluate(
                    "() => document.body?.innerText?.substring(0, 3000) || 'NO BODY'");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("finalUrl", finalUrl);
            result.put("title", title);
            result.put("jobLinks", links != null ? links : List.of());
            result.put("apiRequests", apiRequests);
            result.put("bodyPreview", bodyText);
            return result;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/scrape-all")
    public Map<String, Object> scrapeAll() {
        log.info("Test scrape-all triggered");
        Map<String, Object> results = new LinkedHashMap<>();
        long totalStart = System.currentTimeMillis();

        for (JobScraper scraper : scrapers) {
            for (String company : scraper.companies()) {
                String key = scraper.platform() + "/" + company;
                long start = System.currentTimeMillis();
                try {
                    List<JobPosting> jobs = scraper.scrape(company);
                    long elapsed = System.currentTimeMillis() - start;
                    results.put(key, Map.of("count", jobs.size(), "elapsedMs", elapsed));
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    results.put(key, Map.of("count", 0, "elapsedMs", elapsed,
                            "error", e.getMessage()));
                }
            }
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        results.put("_totalElapsedMs", totalElapsed);
        return results;
    }
}
