package com.github.jingyangyu.swejobnotifier.scraper;

import java.util.List;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;

/**
 * Common interface for all career site scrapers. Each implementation targets a specific ATS
 * platform (Greenhouse, Lever, Workday, etc.).
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
}
