package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Multi-tier local filter that reduces Gemini API calls by pre-classifying job postings.
 *
 * <p>Filter pipeline (applied in order by {@code JobPollingService}):
 *
 * <ul>
 *   <li><b>Tier 0 — Freshness:</b> Drop jobs posted more than {@code job.retention.days} ago.
 *       Prevents re-processing stale postings that never get closed (some stay up 3+ months).
 *   <li><b>Tier 1 — Exclude:</b> Drop titles with senior/staff/manager/intern/frontend/mobile etc.
 *   <li><b>Tier 1.5 — Location:</b> Reject only jobs definitively outside the US. Unknown or
 *       ambiguous locations are accepted to avoid false negatives (missing valid US jobs).
 *   <li><b>Tier 2 — Auto-approve:</b> Titles like "Software Engineer II" are obvious mid-level;
 *       skip Gemini to save API quota.
 *   <li><b>Tier 3 — SWE-relevant gate:</b> Must contain a role keyword + title keyword to proceed
 *       to Gemini classification.
 * </ul>
 */
@Component
public class JobTitleFilter {

    private final int retentionDays;

    public JobTitleFilter(@Value("${job.retention.days:90}") int retentionDays) {
        this.retentionDays = retentionDays;
    }

    // ── Tier 1: Exclude these titles immediately ──
    private static final List<String> EXCLUDE_KEYWORDS =
            List.of(
                    "senior",
                    "sr.",
                    "sr ",
                    "staff",
                    "principal",
                    "lead",
                    "manager",
                    "director",
                    "vp ",
                    "vice president",
                    "head of",
                    "chief",
                    "intern",
                    "internship",
                    "new grad",
                    "co-op",
                    "university",
                    "graduate",
                    "college",
                    "frontend",
                    "front-end",
                    "mobile",
                    "ios",
                    "android");

    // ── Tier 2: Auto-approve obvious mid-level SWE titles ──
    // Matches: "Software Engineer II", "SDE II", "SWE 2", "Backend Developer II", etc.
    private static final Pattern MID_LEVEL_PATTERN =
            Pattern.compile(
                    "(?i)(software|backend|back-end|fullstack|full-stack"
                            + "|full stack|platform|infrastructure|swe|sde)"
                            + ".*?(engineer|developer|eng).*?"
                            + "(\\bii\\b|\\b2\\b|\\bl4\\b|\\be4\\b|\\bic3\\b)");

    // ── Tier 3: SWE-relevant titles that pass to Gemini ──
    private static final List<String> ROLE_KEYWORDS =
            List.of(
                    "software",
                    "backend",
                    "back-end",
                    "full stack",
                    "fullstack",
                    "full-stack",
                    "platform",
                    "infrastructure",
                    "swe",
                    "sde");

    private static final List<String> TITLE_KEYWORDS = List.of("engineer", "developer", "eng");

    // ── US States: Two-letter abbreviations for location validation ──
    private static final List<String> US_STATE_ABBREVIATIONS =
            List.of(
                    "al", "ak", "az", "ar", "ca", "co", "ct", "de", "fl", "ga", "hi", "id", "il",
                    "in", "ia", "ks", "ky", "la", "me", "md", "ma", "mi", "mn", "ms", "mo", "mt",
                    "ne", "nv", "nh", "nj", "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri",
                    "sc", "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy", "dc");

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
        return EXCLUDE_KEYWORDS.stream().anyMatch(title::contains);
    }

    /**
     * Tier 2: Returns true if the title is an obvious mid-level SWE (e.g. "Software Engineer II").
     */
    public boolean isObviousMidLevel(JobPosting job) {
        return MID_LEVEL_PATTERN.matcher(job.getTitle()).find();
    }

    /** Tier 3 gate: Returns true if the title contains a role keyword + title keyword. */
    public boolean isSweRelevant(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        boolean hasRole = ROLE_KEYWORDS.stream().anyMatch(title::contains);
        boolean hasTitle = TITLE_KEYWORDS.stream().anyMatch(title::contains);
        return hasRole && hasTitle;
    }

    /**
     * Tier 1.5: Location filter with a "reject only when certain" policy.
     *
     * <p>Design rationale: We prefer false positives (non-US job slips through) over false negatives
     * (valid US job rejected). A false positive just wastes one Gemini API call; a false negative
     * means the user misses a real opportunity.
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

        // Match "San Francisco, CA" or "Austin, TX" patterns
        for (String state : US_STATE_ABBREVIATIONS) {
            if (loc.contains(", " + state) || loc.endsWith(state)) {
                return true;
            }
        }

        // Only reject locations that explicitly name a non-US country
        String[] nonUsCountries = {
            "uk", "united kingdom", "canada", "germany", "france", "india", "australia",
            "japan", "singapore", "ireland", "mexico", "brazil", "china", "israel"
        };
        for (String country : nonUsCountries) {
            if (loc.contains(country)) {
                return false;
            }
        }

        return true; // Unknown format — accept to avoid false negatives
    }
}
