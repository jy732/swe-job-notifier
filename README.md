# SWE Job Notifier

Automated job posting monitor that scrapes career pages, filters for mid-level and entry-level software engineering roles in the US, classifies ambiguous titles via Gemini AI, and sends email alerts to separate recipient lists by level.

## How It Works

**Pipeline stages:**

1. **Scrape** — Polls 120+ company career pages every 15 minutes using an 8-thread pool
2. **Pre-filter** — Removes stale postings, non-US locations, excluded titles (management, intern, staff+), and non-SWE roles
3. **Dedup** — Loads all known job keys into an in-memory set once per poll cycle for O(1) lookups (no per-job DB queries)
4. **Auto-approve** — Titles containing explicit level indicators (e.g. "SWE II", "L4") are approved without AI; entry-level indicators (e.g. "New Grad", "SWE I") are classified as L3
5. **Gemini classify** — Ambiguous titles are sent to Gemini 2.5 Flash in batches of 50 for 4-way level classification (L3/L4/L3_OR_L4/OTHER). `SignalExtractor` searches both the job title and description for 12 consolidated keywords ("years", "pursuing", "graduating", "new grad", "entry level", "junior", "university", "college", etc.) and returns structured `Signal` records with ~200-char context snippets. The same keyword list drives both local L3 auto-classification and Gemini prompt building. `midLevel` is derived from level: L4 or L3_OR_L4 → true
6. **Persist** — All jobs batch-saved to H2 via `saveAll()` with batch-loaded existing rows (single query, no N+1); Gemini failures are retried on subsequent polls (auto-approved as L4 after 3 failures)
7. **Email alert** — Independent 5-minute scan sends L4 alerts to primary recipients and L3/new-grad alerts to a separate recipient list

### End-to-End Workflow

```mermaid
flowchart TD
    START(["Every 15 min"]) --> SCRAPE["Scrape career pages (8-thread pool)\nGreenhouse | Lever | Workday | Amazon\nGoogle | Apple | Microsoft | Meta | Tesla\nAshby | iCIMS | TikTok"]
    SCRAPE --> FRESH["Filter: remove stale postings"]
    FRESH --> EXCLUDE["Filter: exclude intern, staff+,\nmanagement, director, VP, PhD"]
    EXCLUDE --> LOCATION["Filter: US-based roles only"]
    LOCATION --> SWE["Filter: SWE-relevant titles only"]
    SWE --> DEDUP{"Already\nin DB?"}
    DEDUP -- Yes --> SKIP([Skip])
    DEDUP -- No --> AUTO{"Obvious\nmid-level title?"}
    AUTO -- "L4/L3" --> LEVEL["Level assigned\n(L3/L4)"]
    AUTO -- Ambiguous --> GEMINI["Gemini 2.5 Flash\n4-way classify in batches of 50"]
    GEMINI -- "L3/L4/L3_OR_L4" --> LEVEL
    GEMINI -- OTHER --> OTHER["Level = OTHER"]
    GEMINI -- API failed --> FAILED["Classification failed\nfailures++"]
    LEVEL --> DB[("H2 Database")]
    OTHER --> DB
    FAILED --> DB
    DB --> RETRYCHECK{"Failed\n>= 3 times?"}
    RETRYCHECK -- Yes --> FALLBACK["Auto-approve\nas mid-level"] --> DB
    RETRYCHECK -- No --> REGEMINI["Re-classify\nvia Gemini"] --> DB
    RETRYCHECK -. No failures .-> SCANSTART

    SCANSTART(["Every 5 min"]) --> QUERYL4["Query: midLevel=true\nAND notified=false"]
    QUERYL4 -- Found --> EMAILL4["Send L4 alert\nto primary recipients"]
    SCANSTART --> QUERYL3["Query: level=L3 or L3_OR_L4\nAND notified=false"]
    QUERYL3 -- Found --> EMAILL3["Send L3 alert\nto L3 recipients"]
    EMAILL4 -- Success --> MARK["Mark notified=true"] --> DONE([Done])
    EMAILL3 -- Success --> MARK
    EMAILL4 -- Failure --> RETRY_EMAIL(["Retry in 5 min"])
    EMAILL3 -- Failure --> RETRY_EMAIL

    DAILY1(["Daily 8 AM"]) --> SUMMARY["Email summary of\nrecent activity"]
    DAILY2(["Daily 3 AM"]) --> CLEANUP["Delete jobs\nolder than 90 days"]
```

## Supported Platforms

| Platform | Method | Companies |
|----------|--------|-----------|
| **Greenhouse** (76) | JSON API | Stripe, Airbnb, Cloudflare, Datadog, Twilio, Figma, Discord, Coinbase, Robinhood, Pinterest, Dropbox, DoorDash, Instacart, Databricks, MongoDB, Elastic, GitLab, Roblox, Unity, Lyft, Block, Anthropic, Twitch, Okta, Duolingo, LinkedIn, GoDaddy, Epic Games, Roku, Reddit, Squarespace, Groupon, Yext, Thumbtack, Pure Storage, Lucid Motors, Jane Street, Nextdoor, SoFi, Coursera, Samsara, Verkada, Waymo, Scale AI, Brex, Rubrik, Applied Intuition, The Trade Desk, Lucid Software, Tower Research Capital, Geneva Trading, Bill.com, Qualtrics, ZipRecruiter, IXL Learning, Akuna Capital, Point72, Instabase, Chime, Otter.ai, Flexport, Affirm, Coupang, Ripple, Oscar, Aquatic Capital Management, Glean, Smartsheet, StubHub |
| **Workday** (28) | JSON API | NVIDIA, Salesforce, Intel, Mastercard, Walmart, Adobe, Cisco, PayPal, Qualcomm, Snap, Broadcom, Visa, Dell, Micron, Zoom, Equinix, NXP, IQVIA, Slack, Proofpoint, Abbott, Blue Origin, Cadence, Capital One, Cox, CrowdStrike, HPE, Travelers |
| **Lever** (9) | JSON API | Netflix, Spotify, Palantir, Plaid, Veeva, Zoox, Quantcast, Belvedere Trading, WeRide |
| **Ashby** (2) | JSON API | Whatnot, Notion |
| **iCIMS** (1) | Playwright | Uber |
| **Amazon** | JSON API | Amazon |
| **Google** | Playwright | Google |
| **Apple** | Playwright | Apple |
| **Microsoft** | JSON API (PCSX) | Microsoft |
| **Meta** | GraphQL API | Meta |
| **Tesla** | Playwright | Tesla |
| **TikTok** | Playwright | TikTok |
| **SmartRecruiters** | JSON API | *(configurable)* |
| **OracleCloud** | JSON API | *(configurable)* |

## Prerequisites

- Java 21+
- Maven (wrapper included)
- Chromium (auto-installed by Playwright on first run — needed for Google, Apple, Tesla, TikTok, iCIMS, Uber scrapers)
- Gmail account with [App Password](https://myaccount.google.com/apppasswords)
- Gemini API key (optional — without it, all pre-filtered jobs are approved)

## Setup

1. Clone the repo and create a `.env` file:

```bash
cp .env.example .env  # or create manually
```

2. Configure environment variables in `.env`:

```properties
GEMINI_API_KEY=your-gemini-api-key
EMAIL_USERNAME=you@gmail.com
EMAIL_APP_PASSWORD=your-gmail-app-password
NOTIFICATION_EMAIL=recipient@example.com,another@example.com
NOTIFICATION_EMAIL_L3=newgrad@example.com
```

3. Start the application:

```bash
set -a && source .env && set +a && ./mvnw spring-boot:run
```

Or run in the background:

```bash
set -a && source .env && set +a && nohup ./mvnw spring-boot:run -q > /dev/null 2>&1 &
```

## Scheduled Jobs

| Job | Schedule | Description |
|-----|----------|-------------|
| **Poll** | Every 15 min | Scrape all companies (8-thread pool), filter, classify, persist |
| **Alert scan** | Every 5 min | Email unnotified L4 jobs to primary recipients, L3 jobs to L3 recipients |
| **Daily summary** | 8:00 AM | Summary email of recent activity |
| **Data cleanup** | 3:00 AM | Delete jobs older than 90 days |

## Project Structure

```
src/main/java/com/github/jingyangyu/swejobnotifier/
├── SweJobNotifierApplication.java          # Entry point
├── config/
│   ├── PlaywrightConfig.java               # Shared headless Chromium browser
│   ├── WebClientConfig.java                # Non-blocking HTTP client
│   ├── WorkdayProperties.java              # Workday company configs
│   ├── IcimsProperties.java                # iCIMS company configs
│   └── OracleCloudProperties.java          # OracleCloud company configs
├── controller/
│   └── ScrapeTestController.java           # Manual scrape trigger endpoint
├── model/
│   └── JobPosting.java                     # JPA entity
├── notification/
│   └── EmailNotifier.java                  # Gmail SMTP sender
├── repository/
│   └── JobPostingRepository.java           # Spring Data JPA
├── scraper/
│   ├── JobScraper.java                     # Scraper interface
│   ├── AmazonScraper.java                  # Amazon Jobs JSON API
│   ├── AppleScraper.java                   # Apple Careers (Playwright)
│   ├── AshbyScraper.java                   # Ashby API (Whatnot, Notion)
│   ├── GoogleScraper.java                  # Google Careers (Playwright)
│   ├── GreenhouseScraper.java              # Greenhouse JSON API (76 companies)
│   ├── IcimsScraper.java                   # iCIMS (Playwright)
│   ├── LeverScraper.java                   # Lever JSON API (9 companies)
│   ├── MetaScraper.java                    # Meta Careers GraphQL
│   ├── MicrosoftScraper.java               # Microsoft PCSX API
│   ├── OracleCloudScraper.java             # OracleCloud HCM API
│   ├── SmartRecruitersScraper.java         # SmartRecruiters API
│   ├── TeslaScraper.java                   # Tesla Careers (Playwright)
│   ├── TikTokScraper.java                  # TikTok Careers (Playwright)
│   └── WorkdayScraper.java                 # Workday API (27 companies)
├── service/
│   ├── JobPollingService.java              # Main orchestrator (15-min cycle)
│   ├── NotificationService.java            # Alert scanner (5-min cycle)
│   ├── DailySummaryService.java            # Daily digest email
│   ├── JobCleanupService.java              # 90-day retention cleanup
│   ├── PipelineMetrics.java                # Micrometer counters/gauges
│   └── classification/
│       ├── ClassificationResult.java       # Gemini response + level map
│       ├── FilterKeywords.java             # Title patterns (L3/L4) and excluded keywords
│       ├── GeminiClient.java               # Gemini API client (prompt building + HTTP)
│       ├── JobClassifier.java              # Batch 4-way level classification orchestrator
│       ├── JobTitleFilter.java             # Local pre-filter + auto level classification
│       ├── Signal.java                     # Record: keyword + snippet + source (TITLE/DESCRIPTION)
│       └── SignalExtractor.java            # Extracts level signals from title & JD for Gemini prompts
└── util/
    └── CsvUtil.java                        # CSV export utility
```

## Observability

### Metrics

Exposed via Spring Boot Actuator at `http://localhost:8080/actuator/metrics/job.*`:

- `job.gemini.calls` — Gemini API success/failure counts
- `job.gemini.retries` — Retry attempts
- `job.scrape` — Scrape success/failure counts
- `job.email` — Email delivery success/failure
- `job.pipeline.scraped` — Total jobs scraped
- `job.pipeline.classified` — Jobs classified as mid-level by Gemini
- `job.pipeline.auto_approved` — Jobs auto-approved by title filter
- `job.pipeline.auto_approved_fallback` — Jobs auto-approved after exhausting Gemini retries
- `job.poll.duration` — Poll cycle timing
- `job.unnotified` — Current unnotified job count (gauge)

### Logs

Rolling log files in `logs/app.log` with daily rotation, 30-day retention, and 500MB total size cap.

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

## Configuration

All configuration is in `src/main/resources/application.properties`. Key settings:

| Property | Default | Description |
|----------|---------|-------------|
| `job.poll.cron` | `0 */15 * * * *` | Poll frequency |
| `job.notification.scan.cron` | `0 */5 * * * *` | Alert scan frequency (L4 + L3) |
| `job.summary.cron` | `0 0 8 * * *` | Daily summary time |
| `job.retention.days` | `90` | Days before job cleanup |
| `gemini.model` | `gemini-2.5-flash` | Gemini model for classification |
| `NOTIFICATION_EMAIL_L3` | *(empty)* | L3/new-grad alert recipients (comma-separated) |
| `spring.task.scheduling.pool.size` | `4` | Scheduler thread pool size |

## Tech Stack

- **Framework:** Spring Boot 4.0.5
- **Database:** H2 (file-based)
- **AI:** Google Gemini 2.5 Flash
- **Scraping:** WebClient (JSON APIs) + Playwright 1.52.0 (SPA career sites)
- **Email:** Spring Mail (Gmail SMTP)
- **Metrics:** Micrometer + Spring Boot Actuator
- **Build:** Maven with Spotless (Google Java Format AOSP)
