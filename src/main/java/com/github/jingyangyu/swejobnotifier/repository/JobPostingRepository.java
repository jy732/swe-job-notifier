package com.github.jingyangyu.swejobnotifier.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;

/** Spring Data JPA repository for {@link JobPosting} entities. */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    boolean existsByCompanyAndExternalId(String company, String externalId);

    List<JobPosting> findByDetectedAtAfterOrderByDetectedAtDesc(Instant since);

    List<JobPosting> findByNotifiedFalseOrderByDetectedAtDesc();
}
