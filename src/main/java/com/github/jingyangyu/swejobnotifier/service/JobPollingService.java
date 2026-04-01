package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
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
 * Persist → Send instant alert.
 *
 * <p>Persistence strategy: ALL processed jobs are saved for dedup (with {@code midLevel=false} for
 * rejected ones), so they won't be re-scraped next poll. Jobs that failed Gemini classification are
 * NOT persisted — they stay "unseen" and will be retried on the next poll cycle.
 */
@Slf4j
@Service
public class JobPollingService {

    private final List<JobScraper> scrapers;
    private final JobPostingRepository repository;
    private final JobClassifier classifier;
    private final JobTitleFilter titleFilter;

    public JobPollingService(
            List<JobScraper> scrapers,
            JobPostingRepository repository,
            JobClassifier classifier,
            JobTitleFilter titleFilter) {
        this.scrapers = scrapers;
        this.repository = repository;
        this.classifier = classifier;
        this.titleFilter = titleFilter;
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
                    log.error(
                            "Error polling [{}] {} — skipping company",
                            scraper.platform(),
                            company,
                            e);
                    companiesProcessed++;
                }
            }
        }

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
     * Processes jobs for a single company through the full pipeline.
     *
     * <p>Flow: scrape → pre-filter → dedup → classify + persist. Returns early with zero stats if
     * no unseen jobs remain after dedup (common case on subsequent polls).
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
     * <p>Jobs with obvious mid-level titles (e.g. "Software Engineer II") are auto-approved without
     * calling Gemini, saving API quota. The rest go through Gemini classification. All successfully
     * processed jobs are persisted for dedup; Gemini failures are skipped so they retry next poll.
     */
    private CompanyProcessingResult classifyAndPersist(
            JobScraper scraper, String company, int scrapedCount, List<JobPosting> unseen) {
        // Tier 2: auto-approve obvious mid-level titles; rest goes to Gemini
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
                scraper.platform(), company, unseen.size(), autoApproved.size(), needsGemini.size());

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

        log.info("[{}] {} — persisted {}, skipped {} failed: {} mid-level ({} auto + {} Gemini)",
                scraper.platform(), company, persisted, geminiFailed.size(),
                allApproved.size(), autoApproved.size(), geminiApproved.size());

        return new CompanyProcessingResult(
                allApproved, scrapedCount, unseen.size(), autoApproved.size(),
                needsGemini.size(), persisted, geminiFailed.size());
    }

    /**
     * Persists unseen jobs for dedup tracking.
     *
     * <p>Key behaviors:
     *
     * <ul>
     *   <li>Approved jobs are saved with {@code midLevel=true} — they appear in email alerts.
     *   <li>Rejected jobs are saved with {@code midLevel=false} — they won't appear in alerts but
     *       are stored so dedup skips them on subsequent polls.
     *   <li>Gemini-failed jobs are NOT persisted — they remain "unseen" so the next poll cycle will
     *       re-scrape and retry classification. This handles transient API errors (429, timeouts).
     * </ul>
     *
     * <p>Uses Sets for O(1) lookups instead of List.contains() which would be O(n) per job.
     */
    private int persistJobs(
            List<JobPosting> unseen,
            List<JobPosting> approved,
            List<JobPosting> geminiFailed) {
        Set<String> approvedIds =
                approved.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        Set<String> failedIds =
                geminiFailed.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        int persisted = 0;
        for (JobPosting job : unseen) {
            if (failedIds.contains(job.getExternalId())) {
                continue; // Don't persist — will retry classification next poll
            }
            job.setDetectedAt(Instant.now());
            job.setMidLevel(approvedIds.contains(job.getExternalId()));
            repository.save(job);
            persisted++;
        }
        return persisted;
    }
}
