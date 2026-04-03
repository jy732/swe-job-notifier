package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.List;
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

    public ClassificationResult(List<JobPosting> approved, List<JobPosting> failed) {
        this.approved = approved;
        this.failed = failed;
    }
}
