package com.github.jingyangyu.swejobnotifier.service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.swejobnotifier.scraper.JobScraper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled polling orchestrator that runs every 15 minutes to scrape career sites, pre-filter and
 * dedup job postings, classify them via Gemini, persist to the database, and trigger instant
 * alerts.
 */
@Slf4j
@Service
public class JobPollingService {

    private final List<JobScraper> scrapers;
    private final JobPostingRepository repository;
    private final JobClassifier classifier;
    private final JobTitleFilter titleFilter;
    private final NotificationService notificationService;

    public JobPollingService(
            List<JobScraper> scrapers,
            JobPostingRepository repository,
            JobClassifier classifier,
            JobTitleFilter titleFilter,
            NotificationService notificationService) {
        this.scrapers = scrapers;
        this.repository = repository;
        this.classifier = classifier;
        this.titleFilter = titleFilter;
        this.notificationService = notificationService;
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
                    List<JobPosting> scraped = scraper.scrape(company);
                    totalScraped += scraped.size();

                    // Tier 0: Filter out stale jobs (posted more than retention period ago)
                    List<JobPosting> fresh =
                            scraped.stream().filter(titleFilter::isFresh).toList();

                    // Tier 1: Exclude non-SWE / senior / intern titles
                    List<JobPosting> afterExclude =
                            fresh.stream()
                                    .filter(job -> !titleFilter.shouldExclude(job))
                                    .toList();

                    // Tier 1.5: Filter to US locations only
                    List<JobPosting> usLocationJobs =
                            afterExclude.stream()
                                    .filter(titleFilter::isValidUsLocation)
                                    .toList();

                    // Tier 2 + 3: Must be SWE-relevant (role + title keyword match)
                    List<JobPosting> sweRelevant =
                            usLocationJobs.stream().filter(titleFilter::isSweRelevant).toList();

                    log.info(
                            "[{}] {} — {} scraped → {} fresh → {} after exclude → {} US location → {} SWE-relevant",
                            scraper.platform(),
                            company,
                            scraped.size(),
                            fresh.size(),
                            afterExclude.size(),
                            usLocationJobs.size(),
                            sweRelevant.size());

                    // Dedup against DB
                    List<JobPosting> unseen =
                            sweRelevant.stream()
                                    .filter(
                                            job ->
                                                    !repository.existsByCompanyAndExternalId(
                                                            job.getCompany(), job.getExternalId()))
                                    .toList();
                    totalUnseen += unseen.size();

                    if (unseen.isEmpty()) {
                        log.info("[{}] {} — 0 unseen, skipping", scraper.platform(), company);
                        companiesProcessed++;
                        continue;
                    }

                    // Tier 2: Auto-approve obvious mid-level titles
                    List<JobPosting> autoApproved = new ArrayList<>();
                    List<JobPosting> needsGemini = new ArrayList<>();
                    for (JobPosting job : unseen) {
                        if (titleFilter.isObviousMidLevel(job)) {
                            autoApproved.add(job);
                        } else {
                            needsGemini.add(job);
                        }
                    }
                    totalAutoApproved += autoApproved.size();
                    totalSentToGemini += needsGemini.size();

                    log.info(
                            "[{}] {} — {} unseen: {} auto-approved, {} need Gemini",
                            scraper.platform(),
                            company,
                            unseen.size(),
                            autoApproved.size(),
                            needsGemini.size());

                    // Tier 3: Classify ambiguous titles via Gemini
                    List<JobPosting> geminiApproved = List.of();
                    List<JobPosting> geminiFailed = List.of();
                    if (!needsGemini.isEmpty()) {
                        JobClassifier.ClassificationResult result =
                                classifier.classify(needsGemini);
                        geminiApproved = result.getApproved();
                        geminiFailed = result.getFailed();
                    }

                    // Combine auto-approved + Gemini-approved
                    List<JobPosting> allApproved = new ArrayList<>(autoApproved);
                    allApproved.addAll(geminiApproved);

                    // Persist processed jobs for dedup (skip failed — retry next poll)
                    Set<String> approvedIds =
                            allApproved.stream()
                                    .map(JobPosting::getExternalId)
                                    .collect(Collectors.toSet());
                    Set<String> failedIds =
                            geminiFailed.stream()
                                    .map(JobPosting::getExternalId)
                                    .collect(Collectors.toSet());
                    int persisted = 0;
                    for (JobPosting job : unseen) {
                        if (failedIds.contains(job.getExternalId())) {
                            continue; // don't persist — will retry next poll
                        }
                        job.setDetectedAt(Instant.now());
                        job.setMidLevel(approvedIds.contains(job.getExternalId()));
                        repository.save(job);
                        persisted++;
                    }

                    allNewJobs.addAll(allApproved);
                    companiesProcessed++;
                    log.info(
                            "[{}] {} — persisted {}, skipped {} failed: {} mid-level"
                                    + " ({} auto + {} Gemini), {} rejected",
                            scraper.platform(),
                            company,
                            persisted,
                            geminiFailed.size(),
                            allApproved.size(),
                            autoApproved.size(),
                            geminiApproved.size(),
                            persisted - allApproved.size());
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

        // Send instant alert if new jobs found
        if (!allNewJobs.isEmpty()) {
            log.info(
                    "=== SENDING INSTANT ALERT for {} new mid-level job(s) ===", allNewJobs.size());
            notificationService.sendNewJobAlert(allNewJobs);
        } else {
            log.info("No new mid-level jobs found — skipping instant alert");
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
}
