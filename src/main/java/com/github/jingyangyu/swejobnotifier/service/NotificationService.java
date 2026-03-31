package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends instant alert notifications for newly detected jobs and marks them as notified on success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailNotifier emailNotifier;
    private final JobPostingRepository repository;

    /**
     * Sends an instant alert email for the given new jobs. Only marks jobs as notified if the email
     * sends successfully.
     *
     * @param newJobs the newly classified job postings to notify about
     */
    public void sendNewJobAlert(List<JobPosting> newJobs) {
        if (newJobs.isEmpty()) {
            return;
        }

        boolean sent = emailNotifier.sendNewJobAlert(newJobs);
        if (sent) {
            for (JobPosting job : newJobs) {
                job.setNotified(true);
                repository.save(job);
            }
            log.info("Instant alert sent and {} job(s) marked as notified", newJobs.size());
        } else {
            log.warn(
                    "Instant alert failed — {} job(s) remain unnotified (will be caught by daily"
                            + " summary)",
                    newJobs.size());
        }
    }
}
