package com.github.jingyangyu.swejobnotifier.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Custom Micrometer metrics for the job pipeline.
 *
 * <p>Tracks Gemini API success/failure rates, scrape failures, email delivery, and poll cycle
 * timing. All metrics are exposed via {@code /actuator/metrics/job.*}.
 */
@Component
public class PipelineMetrics {

    private final Counter geminiSuccess;
    private final Counter geminiFail;
    private final Counter geminiRetry;
    private final Counter scrapeSuccess;
    private final Counter scrapeFail;
    private final Counter emailSuccess;
    private final Counter emailFail;
    private final Counter jobsScraped;
    private final Counter jobsClassified;
    private final Counter jobsAutoApproved;
    private final Counter jobsAutoApprovedFallback;
    private final Timer pollCycleTimer;
    private final AtomicInteger unnotifiedGauge;

    public PipelineMetrics(MeterRegistry registry) {
        geminiSuccess =
                Counter.builder("job.gemini.calls")
                        .tag("result", "success")
                        .description("Successful Gemini API calls")
                        .register(registry);
        geminiFail =
                Counter.builder("job.gemini.calls")
                        .tag("result", "failure")
                        .description("Failed Gemini API calls")
                        .register(registry);
        geminiRetry =
                Counter.builder("job.gemini.retries")
                        .description("Gemini API retry attempts")
                        .register(registry);

        scrapeSuccess =
                Counter.builder("job.scrape")
                        .tag("result", "success")
                        .description("Successful company scrapes")
                        .register(registry);
        scrapeFail =
                Counter.builder("job.scrape")
                        .tag("result", "failure")
                        .description("Failed company scrapes")
                        .register(registry);

        emailSuccess =
                Counter.builder("job.email")
                        .tag("result", "success")
                        .description("Successful email sends")
                        .register(registry);
        emailFail =
                Counter.builder("job.email")
                        .tag("result", "failure")
                        .description("Failed email sends")
                        .register(registry);

        jobsScraped =
                Counter.builder("job.pipeline.scraped")
                        .description("Total jobs scraped")
                        .register(registry);
        jobsClassified =
                Counter.builder("job.pipeline.classified")
                        .description("Jobs classified as mid-level by Gemini")
                        .register(registry);
        jobsAutoApproved =
                Counter.builder("job.pipeline.auto_approved")
                        .description("Jobs auto-approved by title filter")
                        .register(registry);
        jobsAutoApprovedFallback =
                Counter.builder("job.pipeline.auto_approved_fallback")
                        .description("Jobs auto-approved after exhausting Gemini retries")
                        .register(registry);

        pollCycleTimer =
                Timer.builder("job.poll.duration")
                        .description("Poll cycle duration")
                        .register(registry);

        unnotifiedGauge = new AtomicInteger(0);
        registry.gauge("job.unnotified", unnotifiedGauge);
    }

    public void recordGeminiSuccess() {
        geminiSuccess.increment();
    }

    public void recordGeminiFail() {
        geminiFail.increment();
    }

    public void recordGeminiRetry() {
        geminiRetry.increment();
    }

    public void recordScrapeSuccess() {
        scrapeSuccess.increment();
    }

    public void recordScrapeFail() {
        scrapeFail.increment();
    }

    public void recordEmailSuccess() {
        emailSuccess.increment();
    }

    public void recordEmailFail() {
        emailFail.increment();
    }

    public void recordJobsScraped(int count) {
        jobsScraped.increment(count);
    }

    public void recordJobsClassified(int count) {
        jobsClassified.increment(count);
    }

    public void recordJobsAutoApproved(int count) {
        jobsAutoApproved.increment(count);
    }

    public void recordAutoApprovedFallback() {
        jobsAutoApprovedFallback.increment();
    }

    public void setUnnotifiedCount(int count) {
        unnotifiedGauge.set(count);
    }

    public Timer.Sample startPollTimer() {
        return Timer.start();
    }

    public void stopPollTimer(Timer.Sample sample) {
        sample.stop(pollCycleTimer);
    }
}
