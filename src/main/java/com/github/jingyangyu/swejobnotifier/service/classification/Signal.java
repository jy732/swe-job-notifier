package com.github.jingyangyu.swejobnotifier.service.classification;

/**
 * A single signal extracted from a job posting's title or description.
 *
 * <p>Represents a keyword match with its surrounding context snippet. Used by {@link
 * SignalExtractor} to produce structured output that {@link GeminiClient} formats into prompts and
 * {@link JobTitleFilter} can use for local classification.
 *
 * @param keyword the signal keyword that was matched (e.g. "years", "new grad")
 * @param snippet the ~200-char context window around the keyword match
 * @param source whether the signal was found in the title or description
 */
public record Signal(String keyword, String snippet, Source source) {

    /** Where the signal keyword was found in the job posting. */
    public enum Source {
        TITLE,
        DESCRIPTION
    }
}
