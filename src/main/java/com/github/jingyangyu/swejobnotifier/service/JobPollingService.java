package com.github.jingyangyu.swejobnotifier.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.github.jingyangyu.swejobnotifier.model.JobPosting;
import com.github.jingyangyu.swejobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.swejobnotifier.scraper.JobScraper;

import lombok.extern.slf4j.Slf4j;

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
    private final NotificationService notificationService;
    private final List<String> filterKeywords;

    public JobPollingService(
            List<JobScraper> scrapers,
            JobPostingRepository repository,
            JobClassifier classifier,
            NotificationService notificationService,
            @Value("${job.filter.keywords:engineer,developer,swe,sde}") String keywordsCsv) {
        this.scrapers = scrapers;
        this.repository = repository;
        this.classifier = classifier;
        this.notificationService = notificationService;
        this.filterKeywords =
                Arrays.stream(keywordsCsv.split(","))
                        .map(String::trim)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .toList();
    }

    /** Polls all configured scrapers on the configured cron schedule. */
    @Scheduled(cron = "${job.poll.cron}")
    public void poll() {
        log.info("Starting job poll cycle");
        List<JobPosting> allNewJobs = new ArrayList<>();

        for (JobScraper scraper : scrapers) {
            for (String company : scraper.companies()) {
                try {
                    List<JobPosting> scraped = scraper.scrape(company);
                    log.info(
                            "[{}] {} — scraped {} job(s)",
                            scraper.platform(),
                            company,
                            scraped.size());

                    // Pre-filter by keywords
                    List<JobPosting> filtered =
                            scraped.stream().filter(this::matchesKeywords).toList();

                    // Dedup against DB
                    List<JobPosting> unseen =
                            filtered.stream()
                                    .filter(
                                            job ->
                                                    !repository.existsByCompanyAndExternalId(
                                                            job.getCompany(),
                                                            job.getExternalId()))
                                    .toList();

                    if (unseen.isEmpty()) {
                        continue;
                    }

                    // Classify via Gemini
                    List<JobPosting> classified = classifier.classify(unseen);

                    // Persist
                    for (JobPosting job : classified) {
                        job.setDetectedAt(Instant.now());
                        repository.save(job);
                    }

                    allNewJobs.addAll(classified);
                    log.info(
                            "[{}] {} — {} new classified job(s)",
                            scraper.platform(),
                            company,
                            classified.size());
                } catch (Exception e) {
                    log.error(
                            "Error polling [{}] {}",
                            scraper.platform(),
                            company,
                            e);
                }
            }
        }

        // Send instant alert if new jobs found
        if (!allNewJobs.isEmpty()) {
            notificationService.sendNewJobAlert(allNewJobs);
        }

        log.info("Job poll cycle complete — {} new job(s) total", allNewJobs.size());
    }

    private boolean matchesKeywords(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        return filterKeywords.stream().anyMatch(title::contains);
    }
}
