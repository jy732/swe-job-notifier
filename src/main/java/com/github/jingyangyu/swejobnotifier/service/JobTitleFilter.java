package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Three-tier title filter that reduces Gemini API calls by pre-classifying job titles locally.
 *
 * <ul>
 *   <li><b>Tier 1 — Exclude:</b> Drop titles with senior/staff/manager/intern etc.
 *   <li><b>Tier 2 — Auto-approve:</b> Titles like "Software Engineer II" are obvious mid-level.
 *   <li><b>Tier 3 — SWE-relevant gate:</b> Must contain a role keyword + title keyword to proceed
 *       to Gemini classification.
 * </ul>
 */
@Component
public class JobTitleFilter {

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
                    "college");

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
}
