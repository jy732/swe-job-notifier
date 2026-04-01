package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled cleanup service that removes stale job postings older than the configured retention
 * period.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCleanupService {

    private final JobPostingRepository repository;

    @Value("${job.retention.days:90}")
    private int retentionDays;

    /**
     * Deletes all job postings posted more than the configured retention period ago. Runs on the
     * configured cleanup schedule (default 3 AM daily).
     */
    @Scheduled(cron = "${job.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanupStaleJobs() {
        log.info("=== JOB CLEANUP START === retention={} days", retentionDays);
        long startTime = System.currentTimeMillis();

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteByPostedDateBefore(cutoff);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("=== JOB CLEANUP COMPLETE === deleted={} stale job(s), elapsed={}ms", deleted, elapsed);
    }
}
