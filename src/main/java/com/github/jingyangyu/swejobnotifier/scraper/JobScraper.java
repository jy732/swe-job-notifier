package com.github.jingyangyu.swejobnotifier.scraper;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.List;

/**
 * Common interface for all career site scrapers. Each implementation targets a specific ATS
 * platform (Greenhouse, Lever, Workday, etc.).
 *
 * <p>Scraping is split into two phases to avoid wasting time on already-seen jobs:
 *
 * <ol>
 *   <li>{@link #scrape(String)} — collects job metadata (title, URL, location). API-based scrapers
 *       include descriptions here since they come free in the response. Playwright scrapers skip
 *       per-job detail page visits at this stage.
 *   <li>{@link #fetchDescriptions(List)} — fetches full job descriptions for a subset of jobs
 *       (typically only unseen jobs that survived dedup). Only Playwright scrapers that require
 *       navigating to individual detail pages need to override this.
 * </ol>
 */
public interface JobScraper {

    /**
     * Returns the name of the ATS platform this scraper targets (e.g. "greenhouse", "lever",
     * "workday").
     */
    String platform();

    /** Returns the list of company identifiers this scraper is configured to poll. */
    List<String> companies();

    /**
     * Scrapes all job postings for the given company from the career site.
     *
     * @param company the company identifier (slug or name, depending on the platform)
     * @return list of scraped job postings; empty list on failure
     */
    List<JobPosting> scrape(String company);

    /**
     * Fetches full job descriptions for the given jobs by visiting their detail pages. Called
     * post-dedup so only unseen jobs pay the cost of per-job page loads.
     *
     * <p>The default implementation is a no-op — API-based scrapers already include descriptions in
     * the {@link #scrape} response. Playwright scrapers that need to navigate to individual detail
     * pages should override this method.
     *
     * @param jobs the unseen jobs whose descriptions need fetching (mutated in place)
     */
    default void fetchDescriptions(List<JobPosting> jobs) {}
}
