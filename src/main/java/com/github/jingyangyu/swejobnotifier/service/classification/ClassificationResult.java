package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Result of Gemini classification, separating approved jobs from failed (unprocessed) ones.
 *
 * <p>The caller uses this to decide persistence strategy:
 *
 * <ul>
 *   <li>{@code approved} — classified as mid-level SWE; persisted with {@code midLevel=true}.
 *   <li>{@code failed} — Gemini could not process (e.g. 429/timeout); NOT persisted so they remain
 *       "unseen" and retry on the next poll cycle.
 * </ul>
 *
 * <p>Jobs that Gemini classified as NOT mid-level are neither in approved nor failed — the caller
 * infers them from the difference and persists them with {@code midLevel=false} for dedup.
 */
@Getter
public class ClassificationResult {
    private final List<JobPosting> approved;
    private final List<JobPosting> failed;

    /** Shadow 4-way level classification: job → L3/L4/L3_OR_L4/OTHER. Empty if not run. */
    private final Map<JobPosting, String> levelMap;

    public ClassificationResult(List<JobPosting> approved, List<JobPosting> failed) {
        this(approved, failed, Collections.emptyMap());
    }

    public ClassificationResult(
            List<JobPosting> approved, List<JobPosting> failed, Map<JobPosting, String> levelMap) {
        this.approved = approved;
        this.failed = failed;
        this.levelMap = levelMap;
    }
}
