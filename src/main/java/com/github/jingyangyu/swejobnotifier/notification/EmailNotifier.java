package com.github.jingyangyu.swejobnotifier.notification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/** Sends HTML email notifications for job alerts and daily summaries via Spring Mail (Gmail SMTP). */
@Slf4j
@Component
public class EmailNotifier {

    private final JavaMailSender mailSender;
    private final String toAddress;
    private final String fromAddress;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public EmailNotifier(
            JavaMailSender mailSender,
            @Value("${job.notification.to}") String toAddress,
            @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.toAddress = toAddress;
        this.fromAddress = fromAddress;
    }

    /**
     * Sends an instant alert email for newly detected jobs.
     *
     * @return true if email was sent successfully, false otherwise
     */
    public boolean sendNewJobAlert(List<JobPosting> newJobs) {
        if (newJobs.isEmpty() || toAddress.isBlank()) {
            return true;
        }

        String subject =
                String.format("[Job Alert] %d new SWE II posting(s) detected", newJobs.size());
        String body = buildAlertHtml(newJobs);
        try {
            sendHtmlEmail(subject, body);
            return true;
        } catch (Exception e) {
            log.error("Failed to send job alert after retries: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Sends a daily summary email of recent job postings. */
    public void sendDailySummary(List<JobPosting> recentJobs) {
        if (toAddress.isBlank()) {
            return;
        }

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
        } catch (Exception e) {
            log.error("Failed to send daily summary after retries: {}", e.getMessage(), e);
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
            sb.append("<td>").append(escape(job.getLocation())).append("</td>");
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
                        sb.append("<td>").append(escape(job.getLocation())).append("</td>");
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
     * Sends an HTML email with retry support for transient failures.
     *
     * @param subject the email subject
     * @param htmlBody the HTML body content
     */
    @Retryable(
            retryFor = {MessagingException.class, MailException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2))
    public void sendHtmlEmail(String subject, String htmlBody) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setFrom(fromAddress);
        helper.setTo(toAddress);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
        log.info("Email sent: {}", subject);
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
}
