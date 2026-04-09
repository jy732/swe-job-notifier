package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.swejobnotifier.scraper.JobScraper;
import com.github.jingyangyu.swejobnotifier.service.classification.ClassificationResult;
import com.github.jingyangyu.swejobnotifier.service.classification.JobClassifier;
import com.github.jingyangyu.swejobnotifier.service.classification.JobTitleFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled polling orchestrator that runs every 15 minutes ({@code job.poll.cron}).
 *
 * <p>Companies within each platform are scraped in parallel (8-thread pool) with a per-company
 * timeout. Single-company Playwright scrapers run sequentially since they share a browser instance.
 */
@Slf4j
@Service
public class JobPollingService {

    static final int MAX_CLASSIFICATION_FAILURES = 3;
    private static final Duration COMPANY_TIMEOUT = Duration.ofMinutes(3);

    private final List<JobScraper> scrapers;
    private final JobPostingRepository repository;
    private final JobClassifier classifier;
    private final JobTitleFilter titleFilter;
    private final PipelineMetrics metrics;
    private final ExecutorService scrapePool = Executors.newFixedThreadPool(8);

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

        // Load all known job keys once for O(1) in-memory dedup (replaces N per-job DB queries)
        Set<String> knownKeys = new HashSet<>(repository.findAllCompanyExternalIdKeys());
        log.info("Loaded {} known job keys for dedup", knownKeys.size());

        for (JobScraper scraper : scrapers) {
            log.info(
                    "Processing platform: {} ({} companies)",
                    scraper.platform(),
                    scraper.companies().size());

            for (var result : scrapeAllCompanies(scraper, knownKeys)) {
                totalScraped += result.scraped;
                totalUnseen += result.unseen;
                totalAutoApproved += result.autoApproved;
                totalSentToGemini += result.sentToGemini;
                allNewJobs.addAll(result.approved);
                companiesProcessed++;
            }
        }

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

    // ── Parallel scraping ──────────────────────────────────────────────────

    /**
     * Scrapes all companies for a platform. Multi-company platforms run in parallel; single-company
     * platforms (Playwright-based, shared browser) run on one thread.
     */
    private List<CompanyResult> scrapeAllCompanies(JobScraper scraper, Set<String> knownKeys) {
        List<String> companies = scraper.companies();
        List<Future<CompanyResult>> futures =
                companies.stream()
                        .map(
                                company ->
                                        scrapePool.submit(
                                                () ->
                                                        safeProcessCompany(
                                                                scraper, company, knownKeys)))
                        .toList();

        List<CompanyResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            results.add(awaitResult(futures.get(i), scraper, companies.get(i)));
        }
        return results;
    }

    private CompanyResult safeProcessCompany(
            JobScraper scraper, String company, Set<String> knownKeys) {
        try {
            return processCompany(scraper, company, knownKeys);
        } catch (Exception e) {
            log.error("Error polling [{}] {} — skipping company", scraper.platform(), company, e);
            return CompanyResult.EMPTY;
        }
    }

    private CompanyResult awaitResult(Future<CompanyResult> future, JobScraper scraper, String co) {
        try {
            return future.get(COMPANY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            metrics.recordScrapeFail();
            if (e instanceof java.util.concurrent.TimeoutException) {
                log.warn(
                        "Timeout after {}s polling [{}] {} — skipping",
                        COMPANY_TIMEOUT.toSeconds(),
                        scraper.platform(),
                        co);
            } else {
                log.error("Error polling [{}] {} — skipping company", scraper.platform(), co, e);
            }
            return CompanyResult.EMPTY;
        }
    }

    // ── Per-company pipeline ───────────────────────────────────────────────

    private CompanyResult processCompany(
            JobScraper scraper, String company, Set<String> knownKeys) {
        List<JobPosting> scraped = scraper.scrape(company);
        List<JobPosting> candidates = applyPreFilters(scraper, company, scraped);
        List<JobPosting> unseen = dedup(candidates, knownKeys);

        if (unseen.isEmpty()) {
            log.info("[{}] {} — 0 unseen, skipping", scraper.platform(), company);
            return new CompanyResult(List.of(), scraped.size(), 0, 0, 0);
        }

        return classifyAndPersist(scraper, company, scraped.size(), unseen);
    }

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
                "[{}] {} — {} scraped → {} fresh → {} after exclude"
                        + " → {} US location → {} SWE-relevant",
                scraper.platform(),
                company,
                scraped.size(),
                fresh.size(),
                afterExclude.size(),
                usLocation.size(),
                sweRelevant.size());
        return sweRelevant;
    }

    private List<JobPosting> dedup(List<JobPosting> jobs, Set<String> knownKeys) {
        return jobs.stream()
                .filter(job -> !knownKeys.contains(job.getCompany() + ":" + job.getExternalId()))
                .toList();
    }

    // ── Classification + persistence ───────────────────────────────────────

    private CompanyResult classifyAndPersist(
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
        log.info(
                "[{}] {} — {} unseen: {} auto-approved, {} need Gemini",
                scraper.platform(),
                company,
                unseen.size(),
                autoApproved.size(),
                needsGemini.size());

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

        log.info(
                "[{}] {} — persisted {}, {} failed: {} mid-level ({} auto + {} Gemini)",
                scraper.platform(),
                company,
                persisted,
                geminiFailed.size(),
                allApproved.size(),
                autoApproved.size(),
                geminiApproved.size());

        return new CompanyResult(
                allApproved, scrapedCount, unseen.size(), autoApproved.size(), needsGemini.size());
    }

    private int persistJobs(
            List<JobPosting> toProcess, List<JobPosting> approved, List<JobPosting> geminiFailed) {
        Set<String> approvedIds =
                approved.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        Set<String> failedIds =
                geminiFailed.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        Instant now = Instant.now();
        List<JobPosting> toPersist = new ArrayList<>();
        for (JobPosting job : toProcess) {
            // Merge with any existing row to avoid unique-constraint violations
            // (the in-memory dedup set is a snapshot — duplicates can slip through)
            JobPosting target =
                    repository
                            .findByCompanyAndExternalId(job.getCompany(), job.getExternalId())
                            .orElse(job);
            if (target.getDetectedAt() == null) {
                target.setDetectedAt(now);
            }
            if (failedIds.contains(job.getExternalId())) {
                target.setClassificationFailures(target.getClassificationFailures() + 1);
                target.setMidLevel(false);
            } else {
                target.setMidLevel(approvedIds.contains(job.getExternalId()));
                target.setClassificationFailures(0);
            }
            toPersist.add(target);
        }
        repository.saveAll(toPersist);
        return toPersist.size();
    }

    // ── Gemini retry ───────────────────────────────────────────────────────

    private void retryFailedClassifications(List<JobPosting> allNewJobs) {
        List<JobPosting> retryJobs =
                repository.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        0, MAX_CLASSIFICATION_FAILURES);
        List<JobPosting> exhaustedJobs =
                repository.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        MAX_CLASSIFICATION_FAILURES - 1, Integer.MAX_VALUE);

        for (JobPosting job : exhaustedJobs) {
            metrics.recordAutoApprovedFallback();
            log.warn(
                    "Auto-approving after {} Gemini failures: [{}] {}",
                    job.getClassificationFailures(),
                    job.getCompany(),
                    job.getTitle());
            job.setMidLevel(true);
            job.setClassificationFailures(0);
            repository.save(job);
            allNewJobs.add(job);
        }

        if (retryJobs.isEmpty()) return;

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

        log.info(
                "=== RETRY COMPLETE === {}/{} approved, {} failed (attempt {}+)",
                result.getApproved().size(),
                retryJobs.size(),
                result.getFailed().size(),
                retryJobs.isEmpty() ? 0 : retryJobs.get(0).getClassificationFailures());
    }

    // ── Result record ──────────────────────────────────────────────────────

    private record CompanyResult(
            List<JobPosting> approved,
            int scraped,
            int unseen,
            int autoApproved,
            int sentToGemini) {
        static final CompanyResult EMPTY = new CompanyResult(List.of(), 0, 0, 0, 0);
    }
}
