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

    // ── Tier 1: Hard exclude — neither L3 nor L4, drop completely ──
    // Seniority: senior/staff/principal/lead/management
    // Role mismatch: frontend, mobile, embedded, security
    // Internship (distinct from new grad/entry-level)
    static final List<String> EXCLUDE_KEYWORDS =
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
                    "co-op",
                    "frontend",
                    "front-end",
                    "mobile",
                    "ios",
                    "android",
                    "embedded",
                    "security");

    // ── Tier 2a: Auto-classify as L4 (mid-level) — skip Gemini ──
    // Matches: "Software Engineer II", "SDE II", "SWE 2", "Backend Developer II", etc.
    static final Pattern L4_PATTERN =
            Pattern.compile(
                    "(?i)(software|backend|back-end|fullstack|full-stack"
                            + "|full stack|platform|infrastructure|swe|sde)"
                            + ".*?(engineer|developer|eng).*?"
                            + "(\\bii\\b|\\b2\\b|\\bl4\\b|\\be4\\b|\\bic3\\b)");

    // ── Tier 2b: Auto-classify as L3 (entry-level / new grad) — skip Gemini ──
    // Matches: "Software Engineer I" (not II), "SDE 1", "Junior SWE", "New Grad Engineer", etc.
    static final Pattern L3_PATTERN =
            Pattern.compile(
                    "(?i)(software|backend|back-end|fullstack|full-stack"
                            + "|full stack|platform|infrastructure|swe|sde)"
                            + ".*?(engineer|developer|eng).*?"
                            + "(\\bi\\b(?!i)|\\b1\\b|\\bl3\\b|\\be3\\b|\\bic2\\b)");

    // Keywords that indicate entry-level regardless of title structure
    static final List<String> L3_KEYWORDS =
            List.of(
                    "new grad",
                    "new graduate",
                    "entry level",
                    "entry-level",
                    "junior",
                    "university",
                    "graduate",
                    "college");

    // Kept for backward compatibility — same regex as L4_PATTERN
    static final Pattern MID_LEVEL_PATTERN = L4_PATTERN;

    // ── Tier 3: SWE-relevant titles must contain one ROLE + one TITLE keyword ──
    static final List<String> ROLE_KEYWORDS =
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
        "uk",
        "united kingdom",
        "canada",
        "germany",
        "france",
        "india",
        "australia",
        "japan",
        "singapore",
        "ireland",
        "mexico",
        "brazil",
        "china",
        "israel",
        "taiwan",
        "south korea",
        "korea",
        "netherlands",
        "sweden",
        "switzerland",
        "poland",
        "czech",
        "denmark",
        "finland",
        "norway",
        "new zealand",
        "philippines",
        "thailand",
        "hong kong",
        "indonesia",
        "malaysia",
        "vietnam",
        "spain",
        "italy",
        "portugal",
        "austria",
        "belgium",
        "romania",
        "hungary",
        "argentina",
        "colombia",
        "chile",
        "peru",
        "egypt",
        "nigeria",
        "kenya",
        "south africa",
        "saudi arabia",
        "united arab emirates",
        "uae",
        "qatar"
    };

    // ISO 2-letter country codes that DON'T collide with US state abbreviations.
    // Codes like CA (California), IN (Indiana), DE (Delaware) are omitted — those
    // countries are already caught by the full-name list above.
    static final String[] NON_US_COUNTRY_CODES = {
        "gb", "ie", "jp", "sg", "au", "fr", "br", "cn", "il", "mx", "kr", "tw",
        "nl", "se", "ch", "at", "pl", "cz", "dk", "fi", "no", "nz", "ph", "th"
    };
}
