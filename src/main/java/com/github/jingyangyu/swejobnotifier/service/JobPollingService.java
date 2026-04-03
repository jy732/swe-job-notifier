package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.swejobnotifier.service.classification.ClassificationResult;
import com.github.jingyangyu.swejobnotifier.service.classification.JobClassifier;
import com.github.jingyangyu.swejobnotifier.service.classification.JobTitleFilter;
import com.github.jingyangyu.swejobnotifier.scraper.JobScraper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled polling orchestrator that runs every 15 minutes ({@code job.poll.cron}).
 *
 * <p>Per-company pipeline: Scrape → Pre-filter (freshness, title, location, SWE relevance) → Dedup
 * against DB → Auto-approve obvious mid-level titles → Classify ambiguous titles via Gemini →
 * Persist.
 *
 * <p>Persistence strategy: ALL jobs are persisted for dedup tracking. Gemini-failed jobs are saved
 * with an incremented {@code classificationFailures} count and retried on subsequent polls. After 3
 * consecutive failures (~45 min of retries), a job is auto-approved — better to send a borderline
 * job than miss a real opportunity.
 */
@Slf4j
@Service
public class JobPollingService {

    private final List<JobScraper> scrapers;
    private final JobPostingRepository repository;
    private final JobClassifier classifier;
    private final JobTitleFilter titleFilter;
    private final PipelineMetrics metrics;

    public JobPollingService(
            List<JobScraper> scrapers,
            JobPostingRepository repository,
            JobClassifier classifier,
            JobTitleFilter titleFilter,
            PipelineMetrics metrics) {
        this.scrapers = scrapers;
        this.repository = repository;
        this.classifier = classifier;
        this.titleFilter = titleFilter;
        this.metrics = metrics;
    }

    /** Result of processing jobs for a single company. */
    @Getter
    private static class CompanyProcessingResult {
        private final List<JobPosting> approved;
        private final int scraped;
        private final int unseen;
        private final int autoApproved;
        private final int sentToGemini;
        private final int persisted;
        private final int geminiFailed;

        CompanyProcessingResult(
                List<JobPosting> approved,
                int scraped,
                int unseen,
                int autoApproved,
                int sentToGemini,
                int persisted,
                int geminiFailed) {
            this.approved = approved;
            this.scraped = scraped;
            this.unseen = unseen;
            this.autoApproved = autoApproved;
            this.sentToGemini = sentToGemini;
            this.persisted = persisted;
            this.geminiFailed = geminiFailed;
        }
    }

    /** Polls all configured scrapers on the configured cron schedule. */
    @Scheduled(cron = "${job.poll.cron}")
    public void poll() {
        log.info("=== POLL CYCLE START ===");
        var timerSample = metrics.startPollTimer();
        long startTime = System.currentTimeMillis();
        List<JobPosting> allNewJobs = new ArrayList<>();
        int totalScraped = 0;
        int totalUnseen = 0;
        int totalAutoApproved = 0;
        int totalSentToGemini = 0;
        int companiesProcessed = 0;

        for (JobScraper scraper : scrapers) {
            log.info(
                    "Processing platform: {} ({} companies)",
                    scraper.platform(),
                    scraper.companies().size());
            for (String company : scraper.companies()) {
                try {
                    CompanyProcessingResult result = processCompany(scraper, company);
                    totalScraped += result.getScraped();
                    totalUnseen += result.getUnseen();
                    totalAutoApproved += result.getAutoApproved();
                    totalSentToGemini += result.getSentToGemini();
                    allNewJobs.addAll(result.getApproved());
                    companiesProcessed++;
                } catch (Exception e) {
                    metrics.recordScrapeFail();
                    log.error(
                            "Error polling [{}] {} — skipping company",
                            scraper.platform(),
                            company,
                            e);
                    companiesProcessed++;
                }
            }
        }

        // Retry jobs that previously failed Gemini classification
        retryFailedClassifications(allNewJobs);

        metrics.stopPollTimer(timerSample);
        metrics.recordJobsScraped(totalScraped);
        metrics.recordJobsAutoApproved(totalAutoApproved);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info(
                "=== POLL CYCLE COMPLETE === companies={}, scraped={}, unseen={},"
                        + " autoApproved={}, sentToGemini={}, newMidLevel={}, elapsed={}s",
                companiesProcessed,
                totalScraped,
                totalUnseen,
                totalAutoApproved,
                totalSentToGemini,
                allNewJobs.size(),
                elapsed / 1000);
    }

    /**
     * Retries Gemini classification for previously failed jobs. Jobs that have reached
     * {@value #MAX_CLASSIFICATION_FAILURES} failures are auto-approved. The rest are re-sent to
     * Gemini.
     */
    private void retryFailedClassifications(List<JobPosting> allNewJobs) {
        List<JobPosting> retryJobs = repository
                .findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        0, MAX_CLASSIFICATION_FAILURES);
        List<JobPosting> exhaustedJobs = repository
                .findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        MAX_CLASSIFICATION_FAILURES - 1, Integer.MAX_VALUE);

        // Auto-approve jobs that exhausted retries
        for (JobPosting job : exhaustedJobs) {
            metrics.recordAutoApprovedFallback();
            log.warn("Auto-approving after {} Gemini failures: [{}] {}",
                    job.getClassificationFailures(), job.getCompany(), job.getTitle());
            job.setMidLevel(true);
            job.setClassificationFailures(0);
            repository.save(job);
            allNewJobs.add(job);
        }

        if (retryJobs.isEmpty()) {
            return;
        }

        log.info("=== RETRY === {} job(s) pending Gemini re-classification", retryJobs.size());
        ClassificationResult result = classifier.classify(retryJobs);

        for (JobPosting job : result.getApproved()) {
            job.setMidLevel(true);
            job.setClassificationFailures(0);
            repository.save(job);
            allNewJobs.add(job);
        }
        for (JobPosting job : result.getFailed()) {
            job.setClassificationFailures(job.getClassificationFailures() + 1);
            repository.save(job);
        }

        log.info("=== RETRY COMPLETE === {}/{} approved, {} failed (attempt {}+)",
                result.getApproved().size(), retryJobs.size(), result.getFailed().size(),
                retryJobs.isEmpty() ? 0 : retryJobs.get(0).getClassificationFailures());
    }

    static final int MAX_CLASSIFICATION_FAILURES = 3;

    /**
     * Processes jobs for a single company through the full pipeline.
     *
     * <p>Flow: scrape → pre-filter → dedup → classify + persist.
     */
    private CompanyProcessingResult processCompany(JobScraper scraper, String company) {
        List<JobPosting> scraped = scraper.scrape(company);
        List<JobPosting> candidates = applyPreFilters(scraper, company, scraped);
        List<JobPosting> unseen = dedup(candidates);

        if (unseen.isEmpty()) {
            log.info("[{}] {} — 0 unseen, skipping", scraper.platform(), company);
            return new CompanyProcessingResult(List.of(), scraped.size(), 0, 0, 0, 0, 0);
        }

        return classifyAndPersist(scraper, company, scraped.size(), unseen);
    }

    /**
     * Applies Tier 0–3 pre-filters and logs the funnel counts at each stage. Filters are applied in
     * order: freshness → title exclusion → US location → SWE relevance. Each stage narrows the set;
     * the funnel log helps diagnose which filter is too aggressive or too lenient.
     */
    private List<JobPosting> applyPreFilters(
            JobScraper scraper, String company, List<JobPosting> scraped) {
        List<JobPosting> fresh = scraped.stream().filter(titleFilter::isFresh).toList();
        List<JobPosting> afterExclude =
                fresh.stream().filter(job -> !titleFilter.shouldExclude(job)).toList();
        List<JobPosting> usLocation =
                afterExclude.stream().filter(titleFilter::isValidUsLocation).toList();
        List<JobPosting> sweRelevant =
                usLocation.stream().filter(titleFilter::isSweRelevant).toList();

        log.info(
                "[{}] {} — {} scraped → {} fresh → {} after exclude → {} US location → {} SWE-relevant",
                scraper.platform(), company, scraped.size(), fresh.size(),
                afterExclude.size(), usLocation.size(), sweRelevant.size());
        return sweRelevant;
    }

    /** Removes jobs already in the database. */
    private List<JobPosting> dedup(List<JobPosting> jobs) {
        return jobs.stream()
                .filter(
                        job ->
                                !repository.existsByCompanyAndExternalId(
                                        job.getCompany(), job.getExternalId()))
                .toList();
    }

    /**
     * Classifies unseen jobs and persists results.
     *
     * <p>Jobs with obvious mid-level titles are auto-approved without Gemini. The rest go through
     * Gemini classification. All jobs are persisted; Gemini failures get an incremented failure
     * count and will be retried by {@link #retryFailedClassifications} on subsequent polls.
     */
    private CompanyProcessingResult classifyAndPersist(
            JobScraper scraper, String company, int scrapedCount, List<JobPosting> unseen) {
        List<JobPosting> autoApproved = new ArrayList<>();
        List<JobPosting> needsGemini = new ArrayList<>();
        for (JobPosting job : unseen) {
            if (titleFilter.isObviousMidLevel(job)) {
                autoApproved.add(job);
            } else {
                needsGemini.add(job);
            }
        }
        log.info("[{}] {} — {} unseen: {} auto-approved, {} need Gemini",
                scraper.platform(), company, unseen.size(),
                autoApproved.size(), needsGemini.size());

        // Gemini classification
        List<JobPosting> geminiApproved = List.of();
        List<JobPosting> geminiFailed = List.of();
        if (!needsGemini.isEmpty()) {
            ClassificationResult result = classifier.classify(needsGemini);
            geminiApproved = result.getApproved();
            geminiFailed = result.getFailed();
        }

        List<JobPosting> allApproved = new ArrayList<>(autoApproved);
        allApproved.addAll(geminiApproved);

        int persisted = persistJobs(unseen, allApproved, geminiFailed);

        log.info("[{}] {} — persisted {}, {} failed: {} mid-level ({} auto + {} Gemini)",
                scraper.platform(), company, persisted, geminiFailed.size(),
                allApproved.size(), autoApproved.size(), geminiApproved.size());

        return new CompanyProcessingResult(
                allApproved, scrapedCount, unseen.size(), autoApproved.size(),
                needsGemini.size(), persisted, geminiFailed.size());
    }

    /**
     * Persists all jobs for dedup tracking and failure counting.
     *
     * <ul>
     *   <li>Approved jobs: {@code midLevel=true}, {@code classificationFailures=0}.
     *   <li>Rejected jobs: {@code midLevel=false}, {@code classificationFailures=0}.
     *   <li>Gemini-failed jobs: {@code midLevel=false}, {@code classificationFailures} incremented
     *       — they will be retried on subsequent polls until auto-approved at the threshold.
     * </ul>
     */
    private int persistJobs(
            List<JobPosting> toProcess,
            List<JobPosting> approved,
            List<JobPosting> geminiFailed) {
        Set<String> approvedIds =
                approved.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        Set<String> failedIds =
                geminiFailed.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        int persisted = 0;
        for (JobPosting job : toProcess) {
            if (job.getDetectedAt() == null) {
                job.setDetectedAt(Instant.now());
            }
            if (failedIds.contains(job.getExternalId())) {
                job.setClassificationFailures(job.getClassificationFailures() + 1);
                job.setMidLevel(false);
            } else {
                job.setMidLevel(approvedIds.contains(job.getExternalId()));
                job.setClassificationFailures(0);
            }
            repository.save(job);
            persisted++;
        }
        return persisted;
    }
}
