package com.github.jingyangyu.swejobnotifier.service.classification;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Constants used by {@link JobTitleFilter} for local pre-classification of job postings.
 *
 * <p>Extracted into a separate class to keep {@code JobTitleFilter} focused on filtering logic
 * rather than walls of keyword lists.
 */
final class FilterKeywords {

    private FilterKeywords() {}

    // ── Tier 1: Exclude titles containing any of these (case-insensitive) ──
    static final List<String> EXCLUDE_KEYWORDS =
            List.of(
                    "senior", "sr.", "sr ",
                    "staff", "principal", "lead",
                    "manager", "director",
                    "vp ", "vice president", "head of", "chief",
                    "intern", "internship", "new grad",
                    "co-op", "university", "graduate", "college",
                    "frontend", "front-end",
                    "mobile", "ios", "android");

    // ── Tier 2: Auto-approve obvious mid-level SWE titles ──
    // Matches: "Software Engineer II", "SDE II", "SWE 2", "Backend Developer II", etc.
    static final Pattern MID_LEVEL_PATTERN =
            Pattern.compile(
                    "(?i)(software|backend|back-end|fullstack|full-stack"
                            + "|full stack|platform|infrastructure|swe|sde)"
                            + ".*?(engineer|developer|eng).*?"
                            + "(\\bii\\b|\\b2\\b|\\bl4\\b|\\be4\\b|\\bic3\\b)");

    // ── Tier 3: SWE-relevant titles must contain one ROLE + one TITLE keyword ──
    static final List<String> ROLE_KEYWORDS =
            List.of(
                    "software", "backend", "back-end",
                    "full stack", "fullstack", "full-stack",
                    "platform", "infrastructure",
                    "swe", "sde");

    static final List<String> TITLE_KEYWORDS = List.of("engineer", "developer", "eng");

    // ── Location: US state abbreviations for "City, ST" pattern matching ──
    static final List<String> US_STATE_ABBREVIATIONS =
            List.of(
                    "al", "ak", "az", "ar", "ca", "co", "ct", "de", "fl", "ga", "hi", "id", "il",
                    "in", "ia", "ks", "ky", "la", "me", "md", "ma", "mi", "mn", "ms", "mo", "mt",
                    "ne", "nv", "nh", "nj", "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri",
                    "sc", "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy", "dc");

    // ── Location: Known non-US countries — only these trigger rejection ──
    static final String[] NON_US_COUNTRIES = {
        "uk", "united kingdom", "canada", "germany", "france", "india", "australia",
        "japan", "singapore", "ireland", "mexico", "brazil", "china", "israel"
    };
}
