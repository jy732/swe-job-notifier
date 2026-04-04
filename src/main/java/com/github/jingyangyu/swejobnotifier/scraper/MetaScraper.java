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
 * Scraper for Meta Careers ({@code metacareers.com/jobsearch}).
 *
 * <p>Meta's career site now uses {@code /jobsearch} for public access and job detail links
 * follow the pattern {@code /profile/job_details/<id>}.
 */
@Slf4j
@Component
public class MetaScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.metacareers.com/jobsearch?q=software+engineer&page=%d";
    private static final int MAX_PAGES = 30;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

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
        java.util.Set<String> seenIds = new java.util.HashSet<>();

        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setUserAgent(USER_AGENT))) {
            Page page = context.newPage();

            for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
                String url = String.format(SEARCH_URL, pageNum);
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(30000));

                // Wait for job detail links
                try {
                    page.waitForSelector("a[href*='/profile/job_details/']",
                            new Page.WaitForSelectorOptions().setTimeout(15000));
                } catch (Exception e) {
                    log.debug("Meta page {}: no job links found, stopping", pageNum);
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, String>> jobs = (List<Map<String, String>>) page.evaluate(
                        "() => {\n"
                        + "  const results = [];\n"
                        + "  const seen = new Set();\n"
                        + "  const links = document.querySelectorAll("
                        + "\"a[href*='/profile/job_details/']\");\n"
                        + "  links.forEach(link => {\n"
                        + "    const href = link.getAttribute('href') || '';\n"
                        + "    const idMatch = href.match(/job_details\\/(\\d+)/);\n"
                        + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                        + "    seen.add(idMatch[1]);\n"
                        + "    const fullText = link.innerText || '';\n"
                        + "    const lines = fullText.split('\\n')"
                        + ".map(l => l.trim()).filter(l => l);\n"
                        + "    const title = lines[0] || '';\n"
                        + "    let location = '';\n"
                        + "    for (let i = 1; i < lines.length; i++) {\n"
                        + "      if (lines[i].includes(',') && lines[i].length < 80"
                        + " && !lines[i].includes('⋅')"
                        + " && !lines[i].includes('+')) {\n"
                        + "        location = lines[i]; break;\n"
                        + "      }\n"
                        + "    }\n"
                        + "    if (!title || title.length < 3) return;\n"
                        + "    results.push({\n"
                        + "      id: idMatch[1],\n"
                        + "      title: title,\n"
                        + "      url: 'https://www.metacareers.com' + href,\n"
                        + "      location: location\n"
                        + "    });\n"
                        + "  });\n"
                        + "  return results;\n"
                        + "}");

                if (jobs == null || jobs.isEmpty()) {
                    log.debug("Meta page {}: no jobs extracted, stopping", pageNum);
                    break;
                }

                int newCount = 0;
                for (Map<String, String> job : jobs) {
                    String id = job.getOrDefault("id", "");
                    if (seenIds.contains(id)) continue;
                    seenIds.add(id);
                    newCount++;
                    allJobs.add(JobPosting.builder()
                            .company("meta")
                            .externalId(id)
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .description("")
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
                }

                log.debug("Meta page {}: extracted {} new jobs", pageNum, newCount);
                if (newCount == 0) break;
            }

            log.info("Meta: scraped {} total job(s)", allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Meta careers", e);
            return allJobs;
        }
    }
}
