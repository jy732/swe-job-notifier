package com.github.jingyangyu.swejobnotifier.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Shared utility for parsing comma-separated value strings. */
public final class CsvUtil {

    private CsvUtil() {}

    /**
     * Parses a comma-separated string into a trimmed, non-empty list of values.
     *
     * @param csv the comma-separated string (may be null or blank)
     * @return list of trimmed non-empty values, or empty list if input is blank
     */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
