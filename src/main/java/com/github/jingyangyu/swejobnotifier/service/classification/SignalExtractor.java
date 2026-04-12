package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts level-relevant signals from job postings by searching titles and descriptions for
 * keywords that indicate experience level (e.g. "years", "new grad", "pursuing").
 *
 * <p>This is a stateless utility — no Spring wiring needed. It consolidates signal keywords that
 * were previously split between {@code FilterKeywords.L3_KEYWORDS} (local classification) and
 * {@code GeminiClient.SIGNAL_KEYWORDS} (prompt building) into a single authoritative list.
 *
 * <p>Each keyword match produces a {@link Signal} with a ~{@value #SIGNAL_WINDOW}-char context
 * snippet and the source (title vs. description). At most {@value #MAX_SIGNALS} signals are
 * returned per job to keep Gemini prompts concise.
 */
public final class SignalExtractor {

    private SignalExtractor() {}

    private static final int SIGNAL_WINDOW = 200;
    private static final int MAX_SIGNALS = 3;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /**
     * Consolidated signal keywords used for both local L3 classification and Gemini prompt
     * building. Ordered roughly by discriminative value — YOE signals first, then academic/new-grad
     * signals.
     */
    static final List<String> SIGNAL_KEYWORDS =
            List.of(
                    // YOE / experience signals (strong level discriminators)
                    "years",
                    // Academic / pipeline signals (suggest L3 / entry-level)
                    "pursuing",
                    "graduating",
                    "graduation",
                    "new grad",
                    "new graduate",
                    "entry level",
                    "entry-level",
                    "junior",
                    "university",
                    "college",
                    "graduate");

    /**
     * Subset of {@link #SIGNAL_KEYWORDS} that indicate entry-level (L3) when found in a title. Used
     * by {@link JobTitleFilter#autoClassifyLevel} for local classification without Gemini.
     */
    static final List<String> L3_TITLE_KEYWORDS =
            List.of(
                    "new grad",
                    "new graduate",
                    "entry level",
                    "entry-level",
                    "junior",
                    "university",
                    "graduate",
                    "college");

    /**
     * Extracts signals from a job posting's title and description.
     *
     * @return list of up to {@value #MAX_SIGNALS} signals, empty if no keywords found
     */
    public static List<Signal> extract(JobPosting job) {
        String title = job.getTitle() != null ? job.getTitle() : "";
        String description = job.getDescription() != null ? job.getDescription() : "";

        List<Signal> signals = new ArrayList<>();
        // Search title first — title signals are higher value since all scrapers have them
        extractFrom(title, Signal.Source.TITLE, signals);
        if (signals.size() < MAX_SIGNALS) {
            extractFrom(description, Signal.Source.DESCRIPTION, signals);
        }
        return Collections.unmodifiableList(signals);
    }

    /**
     * Formats extracted signals into a string for Gemini prompts. Returns "(none)" if no signals
     * were found.
     */
    public static String format(List<Signal> signals) {
        if (signals.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < signals.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append('"').append(signals.get(i).snippet()).append('"');
        }
        return sb.toString();
    }

    /**
     * Returns true if any of the {@link #L3_TITLE_KEYWORDS} appear in the given title. Used for
     * local auto-classification without calling Gemini.
     */
    public static boolean hasL3TitleKeyword(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return L3_TITLE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static void extractFrom(String text, Signal.Source source, List<Signal> signals) {
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
        String lower = clean.toLowerCase(Locale.ROOT);

        // Track snippets to avoid duplicates from overlapping keyword matches
        Set<String> seenSnippets = new LinkedHashSet<>();
        for (String keyword : SIGNAL_KEYWORDS) {
            int idx = 0;
            while (idx < lower.length() && signals.size() < MAX_SIGNALS) {
                int pos = lower.indexOf(keyword, idx);
                if (pos == -1) break;

                int start = Math.max(0, pos - SIGNAL_WINDOW / 2);
                int end = Math.min(clean.length(), pos + keyword.length() + SIGNAL_WINDOW / 2);
                String snippet = clean.substring(start, end).trim().replaceAll("\\s+", " ");

                if (seenSnippets.add(snippet)) {
                    signals.add(new Signal(keyword, snippet, source));
                }
                idx = pos + keyword.length();
            }
            if (signals.size() >= MAX_SIGNALS) break;
        }
    }
}
