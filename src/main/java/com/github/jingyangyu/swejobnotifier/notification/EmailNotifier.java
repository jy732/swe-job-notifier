package com.github.jingyangyu.swejobnotifier.notification;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends HTML email notifications for job alerts and daily summaries via Spring Mail (Gmail SMTP).
 */
@Slf4j
@Component
public class EmailNotifier {

    private final JavaMailSender mailSender;
    private final String toAddress;
    private final String fromAddress;
    private final RetryTemplate retryTemplate;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public EmailNotifier(
            JavaMailSender mailSender,
            @Value("${job.notification.to}") String toAddress,
            @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.toAddress = toAddress;
        this.fromAddress = fromAddress;
        this.retryTemplate =
                RetryTemplate.builder()
                        .maxAttempts(3)
                        .exponentialBackoff(2000, 2, 8000)
                        .retryOn(MessagingException.class)
                        .retryOn(MailException.class)
                        .build();
    }

    /**
     * Sends an instant alert email for newly detected jobs.
     *
     * @return true if email was sent successfully, false otherwise
     */
    public boolean sendNewJobAlert(List<JobPosting> newJobs) {
        if (newJobs.isEmpty() || toAddress.isBlank()) {
            if (toAddress.isBlank()) {
                log.warn(
                        "Notification email not configured (toAddress blank) — skipping job alert");
            }
            return true;
        }

        String subject =
                String.format("[Job Alert] %d new SWE II posting(s) detected", newJobs.size());
        log.info("Preparing job alert email: to={}, subject={}", toAddress, subject);
        String body = buildAlertHtml(newJobs);
        try {
            sendHtmlEmail(subject, body);
            log.info("Job alert email sent successfully to {}", toAddress);
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to send job alert email to {} after retries: {}",
                    toAddress,
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /**
     * Sends a daily summary email of recent job postings.
     *
     * @return true if email was sent successfully, false otherwise
     */
    public boolean sendDailySummary(List<JobPosting> recentJobs) {
        if (toAddress.isBlank()) {
            log.warn(
                    "Notification email not configured (toAddress blank) — skipping daily summary");
            return false;
        }
        log.info("Preparing daily summary email: to={}, jobs={}", toAddress, recentJobs.size());

        String subject;
        String body;
        if (recentJobs.isEmpty()) {
            subject = "[Job Summary] No new SWE II postings in the last 24 hours";
            body =
                    "<p>No new Software Engineer II postings were detected in the last 24"
                            + " hours.</p>";
        } else {
            subject =
                    String.format(
                            "[Job Summary] %d new SWE II posting(s) in the last 24 hours",
                            recentJobs.size());
            body = buildSummaryHtml(recentJobs);
        }
        try {
            sendHtmlEmail(subject, body);
            return true;
        } catch (Exception e) {
            log.error("Failed to send daily summary after retries: {}", e.getMessage(), e);
            return false;
        }
    }

    private String buildAlertHtml(List<JobPosting> jobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>New Job Postings Detected</h2>");
        sb.append(
                "<table border='1' cellpadding='8' cellspacing='0'"
                        + " style='border-collapse:collapse;'>");
        sb.append("<tr><th>Company</th><th>Title</th><th>Location</th><th>Link</th></tr>");
        for (JobPosting job : jobs) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(job.getCompany())).append("</td>");
            sb.append("<td>").append(escape(job.getTitle())).append("</td>");
            sb.append("<td>").append(formatLocation(job.getLocation())).append("</td>");
            sb.append("<td><a href='").append(escape(job.getUrl())).append("'>Apply</a></td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildSummaryHtml(List<JobPosting> jobs) {
        Map<String, List<JobPosting>> byCompany =
                jobs.stream().collect(Collectors.groupingBy(JobPosting::getCompany));

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Daily Job Summary</h2>");
        sb.append("<p>")
                .append(jobs.size())
                .append(" new posting(s) found in the last 24 hours.</p>");

        byCompany.forEach(
                (company, postings) -> {
                    sb.append("<h3>")
                            .append(escape(company))
                            .append(" (")
                            .append(postings.size())
                            .append(")</h3>");
                    sb.append(
                            "<table border='1' cellpadding='8' cellspacing='0'"
                                    + " style='border-collapse:collapse;'>");
                    sb.append(
                            "<tr><th>Title</th><th>Location</th><th>Detected</th><th>Link</th></tr>");
                    for (JobPosting job : postings) {
                        sb.append("<tr>");
                        sb.append("<td>").append(escape(job.getTitle())).append("</td>");
                        sb.append("<td>").append(formatLocation(job.getLocation())).append("</td>");
                        sb.append("<td>")
                                .append(DATE_FMT.format(job.getDetectedAt()))
                                .append("</td>");
                        sb.append("<td><a href='")
                                .append(escape(job.getUrl()))
                                .append("'>Apply</a></td>");
                        sb.append("</tr>");
                    }
                    sb.append("</table>");
                });
        return sb.toString();
    }

    /**
     * Sends an HTML email with retry support for transient failures. Retries up to 3 times with
     * exponential backoff (2s, 4s, 8s) on {@link MessagingException} and {@link MailException}.
     *
     * @param subject the email subject
     * @param htmlBody the HTML body content
     */
    private void sendHtmlEmail(String subject, String htmlBody) throws Exception {
        retryTemplate.execute(
                context -> {
                    if (context.getRetryCount() > 0) {
                        log.warn(
                                "Email retry attempt {} for: {}", context.getRetryCount(), subject);
                    }
                    log.info("Connecting to SMTP server to send: {}", subject);
                    MimeMessage message = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true);
                    helper.setFrom(fromAddress);
                    helper.setTo(toAddress);
                    helper.setSubject(subject);
                    helper.setText(htmlBody, true);
                    mailSender.send(message);
                    log.info("Email SENT successfully: {}", subject);
                    return null;
                });
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String formatLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        // Remote positions
        if (location.toLowerCase().contains("remote")) {
            return "Remote";
        }
        // US locations: extract city, state (format: "City, ST" or "City, State")
        return escape(location);
    }
}
