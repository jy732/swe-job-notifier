package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scans for unnotified jobs every 5 minutes and sends alert emails by level.
 *
 * <p>Decoupled from the poll cycle: {@code JobPollingService} scrapes, filters, classifies, and
 * persists jobs with a {@code level} (L3/L4/L3_OR_L4/OTHER). This service independently picks up
 * unnotified jobs by level and sends alerts. This design means:
 *
 * <ul>
 *   <li>No inline email sending during the poll — poll failures don't affect notifications.
 *   <li>No separate retry logic needed — the same scan naturally retries on the next run.
 *   <li>Worst-case alert latency is ~5 minutes after a job is persisted.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailNotifier emailNotifier;
    private final JobPostingRepository repository;
    private final PipelineMetrics metrics;

    /**
     * Scans for unnotified mid-level jobs and sends an alert email if any are found. Runs every 5
     * minutes. No-ops silently when nothing to send.
     */
    @Scheduled(cron = "${job.notification.scan.cron:0 */5 * * * *}")
    public void scanAndNotify() {
        // L4 alerts (mid-level: L4 or L3_OR_L4)
        List<JobPosting> l4Unnotified = repository.findUnnotifiedMidLevelJobs();

        // L3 alerts (new flow)
        List<JobPosting> l3Unnotified = repository.findUnnotifiedL3Jobs();

        if (l4Unnotified.isEmpty() && l3Unnotified.isEmpty()) {
            return;
        }

        List<JobPosting> toMarkNotified = new ArrayList<>();

        // Send L4 alert
        if (!l4Unnotified.isEmpty()) {
            log.info(
                    "=== ALERT SCAN === {} unnotified L4 job(s) found, sending...",
                    l4Unnotified.size());
            boolean sent = emailNotifier.sendNewJobAlert(l4Unnotified);
            if (sent) {
                metrics.recordEmailSuccess();
                toMarkNotified.addAll(l4Unnotified);
                log.info("L4 alert SENT — {} job(s)", l4Unnotified.size());
            } else {
                metrics.recordEmailFail();
                log.warn(
                        "L4 alert FAILED — {} job(s) remain unnotified, will retry in 5 min",
                        l4Unnotified.size());
            }
        }

        // Send L3 alert
        if (!l3Unnotified.isEmpty()) {
            log.info(
                    "=== ALERT SCAN === {} unnotified L3 job(s) found, sending...",
                    l3Unnotified.size());
            boolean sent = emailNotifier.sendNewL3JobAlert(l3Unnotified);
            if (sent) {
                toMarkNotified.addAll(l3Unnotified);
                log.info("L3 alert SENT — {} job(s)", l3Unnotified.size());
            } else {
                log.warn(
                        "L3 alert FAILED — {} job(s) remain unnotified, will retry in 5 min",
                        l3Unnotified.size());
            }
        }

        // Mark notified (deduplicate in case L3_OR_L4 jobs appear in both lists)
        toMarkNotified.stream()
                .map(JobPosting::getId)
                .distinct()
                .forEach(
                        id -> {
                            repository
                                    .findById(id)
                                    .ifPresent(
                                            job -> {
                                                job.setNotified(true);
                                                repository.save(job);
                                            });
                        });

        long totalMarked = toMarkNotified.stream().map(JobPosting::getId).distinct().count();
        log.info("Alert scan complete — {} job(s) marked as notified", totalMarked);

        metrics.setUnnotifiedCount(repository.findUnnotifiedMidLevelJobs().size());
    }
}
