package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local filter and classifier that reduces Gemini API calls by pre-processing job postings.
 *
 * <p>Provides two categories of methods, used at different pipeline stages:
 *
 * <ul>
 *   <li><b>Pre-filters</b> (used by {@code JobPollingService#applyPreFilters}):
 *       <ul>
 *         <li>{@link #isFresh} — Drop stale postings older than {@code job.retention.days}.
 *         <li>{@link #shouldExclude} — Drop senior/staff/manager/intern/frontend/mobile titles.
 *         <li>{@link #isValidUsLocation} — Reject definitively non-US locations.
 *         <li>{@link #isSweRelevant} — Require role + title keyword for SWE relevance.
 *       </ul>
 *   <li><b>Classification</b> (used by {@link ClassificationPipeline} Stage 1):
 *       <ul>
 *         <li>{@link #autoClassifyLevel} — Assign L3/L4 from title patterns and keywords.
 *       </ul>
 * </ul>
 *
 * <p>All keyword lists and patterns are defined in {@link FilterKeywords}.
 */
@Component
public class JobTitleFilter {

    private final int retentionDays;

    public JobTitleFilter(@Value("${job.retention.days:90}") int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * Tier 0: Returns true if the job is fresh enough (posted within retention period). Filters out
     * stale jobs older than the configured retention days.
     */
    public boolean isFresh(JobPosting job) {
        if (job.getPostedDate() == null) {
            return true; // Unknown posted date — accept to avoid false negatives
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        return job.getPostedDate().isAfter(cutoff);
    }

    /** Tier 1: Returns true if the title should be excluded (senior/staff/intern/manager etc.). */
    public boolean shouldExclude(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        return FilterKeywords.EXCLUDE_KEYWORDS.stream().anyMatch(title::contains);
    }

    /**
     * Tier 2: Auto-classifies a job's level from the title alone.
     *
     * @return "L4" for obvious mid-level, "L3" for obvious entry-level/new-grad, or null if
     *     ambiguous (needs Gemini).
     */
    public String autoClassifyLevel(JobPosting job) {
        String title = job.getTitle();
        if (FilterKeywords.L4_PATTERN.matcher(title).find()) {
            return "L4";
        }
        if (FilterKeywords.L3_PATTERN.matcher(title).find()) {
            return "L3";
        }
        if (SignalExtractor.hasL3TitleKeyword(title)) {
            return "L3";
        }
        return null;
    }

    /** Tier 3 gate: Returns true if the title contains a role keyword + title keyword. */
    public boolean isSweRelevant(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        boolean hasRole = FilterKeywords.ROLE_KEYWORDS.stream().anyMatch(title::contains);
        boolean hasTitle = FilterKeywords.TITLE_KEYWORDS.stream().anyMatch(title::contains);
        return hasRole && hasTitle;
    }

    /**
     * Tier 1.5: Location filter with a "reject only when certain" policy.
     *
     * <p>Design rationale: We prefer false positives (non-US job slips through) over false
     * negatives (valid US job rejected). A false positive just wastes one Gemini API call; a false
     * negative means the user misses a real opportunity.
     *
     * <p>Detection strategy:
     *
     * <ol>
     *   <li>Accept if location contains "remote" (any variant).
     *   <li>Accept if location matches "City, XX" where XX is a US state abbreviation.
     *   <li>Reject if location contains a known non-US country name.
     *   <li>Accept anything else (unknown format, blank, or ambiguous).
     * </ol>
     *
     * @return true if the job should proceed through the pipeline; false only for definitively
     *     non-US locations
     */
    public boolean isValidUsLocation(JobPosting job) {
        String location = job.getLocation();
        if (location == null || location.isBlank()) {
            return true;
        }

        String loc = location.toLowerCase(Locale.ROOT);

        if (loc.contains("remote")) {
            return true;
        }

        // Match "San Francisco, CA" or "Austin, TX" patterns — require state abbrev to be
        // a complete token so "India" doesn't false-match Indiana ("in")
        for (String state : FilterKeywords.US_STATE_ABBREVIATIONS) {
            String withComma = ", " + state;
            if (loc.endsWith(withComma)
                    || loc.contains(withComma + " ")
                    || loc.contains(withComma + ",")) {
                return true;
            }
        }

        // Only reject locations that explicitly name a non-US country
        for (String country : FilterKeywords.NON_US_COUNTRIES) {
            if (loc.contains(country)) {
                return false;
            }
        }

        // Reject "Dublin, IE" style locations using ISO country codes (no US-state collisions)
        for (String code : FilterKeywords.NON_US_COUNTRY_CODES) {
            if (loc.endsWith(", " + code)) {
                return false;
            }
        }

        return true; // Unknown format — accept to avoid false negatives
    }
}
