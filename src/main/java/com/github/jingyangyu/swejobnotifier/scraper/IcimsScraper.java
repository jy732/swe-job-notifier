package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.config.IcimsProperties;
import com.github.jingyangyu.swejobnotifier.config.IcimsProperties.IcimsCompany;
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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for companies using iCIMS career portals. Modern iCIMS portals are React SPAs that
 * require a headless browser to render job listings.
 */
@Slf4j
@Component
public class IcimsScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private final Browser browser;
    private final IcimsProperties properties;

    public IcimsScraper(Browser browser, IcimsProperties properties) {
        this.browser = browser;
        this.properties = properties;
        log.info(
                "iCIMS scraper initialized with {} company(ies)", properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "icims";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(IcimsCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Renders the iCIMS career portal via Playwright with a custom user agent. Modern iCIMS
     * portals are React SPAs that require JavaScript rendering to display job listings. Per-company
     * config provides the portal subdomain (or custom domain) for URL construction. Paginates
     * through search results by interacting with the rendered DOM. Returns an empty list if no
     * config is found for the given company.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<IcimsCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No iCIMS config found for company: {}", company);
            return Collections.emptyList();
        }

        IcimsCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();

        try (BrowserContext context =
                browser.newContext(
                        new Browser.NewContextOptions()
                                .setUserAgent(USER_AGENT)
                                .setViewportSize(1920, 1080))) {
            Page page = context.newPage();

            String searchUrl = config.baseUrl() + "/jobs/search?ss=1&searchRelation=keyword_all";
            page.navigate(
                    searchUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(30000));

            // Wait for job cards to render
            try {
                page.waitForSelector(
                        "a[href*='/jobs/']", new Page.WaitForSelectorOptions().setTimeout(15000));
            } catch (Exception e) {
                log.debug("iCIMS [{}]: no job links found after waiting", company);
                log.info("iCIMS [{}]: scraped 0 total job(s)", company);
                return allJobs;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> jobs =
                    (List<Map<String, String>>)
                            page.evaluate(
                                    "() => {\n"
                                            + "  const results = [];\n"
                                            + "  const seen = new Set();\n"
                                            + "  const links = document.querySelectorAll("
                                            + "\"a[href*='/jobs/']\");\n"
                                            + "  links.forEach(link => {\n"
                                            + "    const href = link.getAttribute('href') || '';\n"
                                            + "    const idMatch = href.match("
                                            + "/\\/jobs\\/(\\d+)/);\n"
                                            + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                                            + "    seen.add(idMatch[1]);\n"
                                            + "    const title = link.textContent.trim();\n"
                                            + "    if (!title || title.length < 3) return;\n"
                                            + "    const card = link.closest('li')"
                                            + " || link.closest('div') || link;\n"
                                            + "    let location = '';\n"
                                            + "    const spans = card.querySelectorAll('span');\n"
                                            + "    for (const s of spans) {\n"
                                            + "      const text = s.textContent.trim();\n"
                                            + "      if (text && text !== title"
                                            + " && text.length < 100"
                                            + " && text.length > 2) {\n"
                                            + "        location = text; break;\n"
                                            + "      }\n"
                                            + "    }\n"
                                            + "    const fullUrl = href.startsWith('http')"
                                            + " ? href : window.location.origin + href;\n"
                                            + "    results.push({\n"
                                            + "      id: idMatch[1],\n"
                                            + "      title: title,\n"
                                            + "      url: fullUrl,\n"
                                            + "      location: location\n"
                                            + "    });\n"
                                            + "  });\n"
                                            + "  return results;\n"
                                            + "}");

            if (jobs != null) {
                for (Map<String, String> job : jobs) {
                    allJobs.add(
                            JobPosting.builder()
                                    .company(company)
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
            log.error("Failed to scrape iCIMS for company: {}", company, e);
        }

        log.info("iCIMS [{}]: scraped {} total job(s)", company, allJobs.size());
        return allJobs;
    }
}
