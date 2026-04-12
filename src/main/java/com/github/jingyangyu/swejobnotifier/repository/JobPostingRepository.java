package com.github.jingyangyu.swejobnotifier.repository;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data JPA repository for {@link JobPosting} entities. */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /** Checks if a job with the given natural key already exists. */
    boolean existsByCompanyAndExternalId(String company, String externalId);

    /** Returns all compound keys (company + ':' + externalId) for bulk in-memory dedup. */
    @Query("SELECT jp.company || ':' || jp.externalId FROM JobPosting jp")
    Set<String> findAllCompanyExternalIdKeys();

    /** Batch lookup of existing jobs by their natural keys (company + externalId). */
    @Query("SELECT jp FROM JobPosting jp WHERE jp.company || ':' || jp.externalId IN ?1")
    List<JobPosting> findByCompanyExternalIdKeys(Set<String> keys);

    /** Recent mid-level jobs for daily summary. */
    List<JobPosting> findByMidLevelTrueAndDetectedAtAfterOrderByDetectedAtDesc(Instant since);

    /** Unnotified mid-level jobs for the 5-minute alert scan. */
    List<JobPosting> findByMidLevelTrueAndNotifiedFalseOrderByDetectedAtDesc();

    /** Finds unnotified L3 jobs (entry-level). L3_OR_L4 is included since it goes to both. */
    @Query(
            "SELECT jp FROM JobPosting jp WHERE jp.notified = false"
                    + " AND (jp.level = 'L3' OR jp.level = 'L3_OR_L4')"
                    + " ORDER BY jp.detectedAt DESC")
    List<JobPosting> findUnnotifiedL3Jobs();

    /** Recent L3 jobs for daily summary. */
    @Query(
            "SELECT jp FROM JobPosting jp WHERE jp.detectedAt > ?1"
                    + " AND (jp.level = 'L3' OR jp.level = 'L3_OR_L4')"
                    + " ORDER BY jp.detectedAt DESC")
    List<JobPosting> findRecentL3Jobs(Instant since);

    /** Finds jobs that failed Gemini classification but haven't exhausted retries yet. */
    List<JobPosting> findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
            int min, int max);

    /** Deletes jobs posted before the cutoff for data retention cleanup. */
    @Modifying
    @Query("DELETE FROM JobPosting jp WHERE jp.postedDate < ?1")
    int deleteByPostedDateBefore(Instant cutoff);
}
