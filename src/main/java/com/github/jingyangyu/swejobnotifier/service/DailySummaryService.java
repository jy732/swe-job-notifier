package com.github.jingyangyu.swejobnotifier.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends a daily summary email at 8 AM containing all jobs detected in the last 24 hours plus any
 * other jobs that were never successfully notified (safety net for failed alerts).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final JobPostingRepository repository;
    private final EmailNotifier emailNotifier;

    /** Sends the daily summary email on the configured cron schedule. */
    @Scheduled(cron = "${job.summary.cron}")
    public void sendDailySummary() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<JobPosting> recentJobs =
                repository.findByDetectedAtAfterOrderByDetectedAtDesc(since);
        List<JobPosting> unnotifiedJobs =
                repository.findByNotifiedFalseOrderByDetectedAtDesc();

        // Merge both lists, dedup by id
        Set<Long> seen = new LinkedHashSet<>();
        List<JobPosting> allJobs = new ArrayList<>();
        for (JobPosting job : recentJobs) {
            if (seen.add(job.getId())) {
                allJobs.add(job);
            }
        }
        for (JobPosting job : unnotifiedJobs) {
            if (seen.add(job.getId())) {
                allJobs.add(job);
            }
        }

        log.info(
                "Daily summary: {} recent job(s), {} unnotified job(s), {} total after dedup",
                recentJobs.size(),
                unnotifiedJobs.size(),
                allJobs.size());

        if (allJobs.isEmpty()) {
            log.info("No jobs to include in daily summary — skipping email");
            return;
        }

        emailNotifier.sendDailySummary(allJobs);

        // Mark previously unnotified jobs as notified after successful summary send
        for (JobPosting job : unnotifiedJobs) {
            if (!job.isNotified()) {
                job.setNotified(true);
                repository.save(job);
            }
        }
    }
}
