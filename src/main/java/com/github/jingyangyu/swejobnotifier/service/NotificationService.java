package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scans for unnotified mid-level jobs every 5 minutes and sends alert emails.
 *
 * <p>Decoupled from the poll cycle: {@code JobPollingService} scrapes, filters, classifies, and
 * persists jobs with {@code midLevel=true/false}. This service independently picks up any
 * {@code midLevel=true AND notified=false} jobs and sends alerts. This design means:
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

    /**
     * Scans for unnotified mid-level jobs and sends an alert email if any are found.
     * Runs every 5 minutes. No-ops silently when nothing to send.
     */
    @Scheduled(cron = "${job.notification.scan.cron:0 */5 * * * *}")
    public void scanAndNotify() {
        List<JobPosting> unnotified =
                repository.findByMidLevelTrueAndNotifiedFalseOrderByDetectedAtDesc();
        if (unnotified.isEmpty()) {
            return;
        }

        log.info("=== ALERT SCAN === {} unnotified job(s) found, sending...", unnotified.size());
        boolean sent = emailNotifier.sendNewJobAlert(unnotified);
        if (sent) {
            for (JobPosting job : unnotified) {
                job.setNotified(true);
                repository.save(job);
            }
            log.info("Alert SENT — {} job(s) marked as notified", unnotified.size());
        } else {
            log.warn("Alert FAILED — {} job(s) remain unnotified, will retry in 5 min",
                    unnotified.size());
        }
    }
}
