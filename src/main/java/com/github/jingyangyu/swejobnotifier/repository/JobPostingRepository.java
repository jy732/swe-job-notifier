package com.github.jingyangyu.swejobnotifier.repository;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data JPA repository for {@link JobPosting} entities. */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    boolean existsByCompanyAndExternalId(String company, String externalId);

    List<JobPosting> findByMidLevelTrueAndDetectedAtAfterOrderByDetectedAtDesc(Instant since);

    List<JobPosting> findByMidLevelTrueAndNotifiedFalseOrderByDetectedAtDesc();

    /** Finds jobs that failed Gemini classification but haven't exhausted retries yet. */
    List<JobPosting> findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
            int min, int max);

    @Modifying
    @Query("DELETE FROM JobPosting jp WHERE jp.postedDate < ?1")
    int deleteByPostedDateBefore(Instant cutoff);
}
