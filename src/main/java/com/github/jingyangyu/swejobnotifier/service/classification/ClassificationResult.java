package com.github.jingyangyu.swejobnotifier.service.classification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Result of Gemini 4-way level classification (L3/L4/L3_OR_L4/OTHER).
 *
 * <p>Contains two outputs:
 *
 * <ul>
 *   <li>{@code levelMap} — maps each successfully classified job to its level string.
 *   <li>{@code failed} — jobs that Gemini could not process (e.g. 429/timeout); NOT persisted so
 *       they remain "unseen" and retry on the next poll cycle.
 * </ul>
 *
 * <p>L4 and L3_OR_L4 are treated as mid-level for notification purposes.
 */
@Getter
public class ClassificationResult {

    /** 4-way level classification: job → L3/L4/L3_OR_L4/OTHER. */
    private final Map<JobPosting, String> levelMap;

    /** Jobs where Gemini API calls failed after retries. */
    private final List<JobPosting> failed;

    public ClassificationResult(Map<JobPosting, String> levelMap, List<JobPosting> failed) {
        this.levelMap = levelMap;
        this.failed = failed;
    }
}
