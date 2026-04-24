# SWE Job Notifier

Automated job posting monitor that scrapes career pages, filters for mid-level and entry-level software engineering roles in the US, classifies ambiguous titles via Gemini AI, and sends email alerts to separate recipient lists by level.

## How It Works

**Pipeline stages:**

1. **Scrape** — Polls 135+ company career pages every 15 minutes using an 8-thread pool. Playwright scrapers collect metadata only (title, URL, location) — descriptions are deferred to post-dedup. API scrapers include descriptions for free in the response.
2. **Pre-filter** — Removes stale postings, non-US locations, excluded titles (management, intern, staff+), and non-SWE roles
3. **Dedup** — Loads all known job keys into an in-memory set once per poll cycle for O(1) lookups (no per-job DB queries)
4. **Fetch descriptions** — Playwright scrapers (Google, Tesla, TikTok, iCIMS) open a fresh browser context and visit detail pages only for unseen jobs. This avoids fetching ~100 descriptions for already-seen jobs that would be discarded after dedup.
5. **Classify** — Three-stage `ClassificationPipeline` assigns a level (L3/L4/L3_OR_L4/OTHER) to each job. L4 and L3_OR_L4 are treated as mid-level for notifications. See [Classification Pipeline](#classification-pipeline) for full details.
6. **Persist** — All jobs batch-saved to H2 via `saveAll()` with batch-loaded existing rows (single query, no N+1); Gemini failures are retried on subsequent polls (auto-approved as L4 after 3 failures)
7. **Email alert** — Independent 5-minute scan sends L4 alerts to primary recipients and L3/new-grad alerts to a separate recipient list

### End-to-End Workflow

```mermaid
flowchart TD
    START(["Every 15 min"]) --> SCRAPE["Scrape career pages (8-thread pool)\nGreenhouse | Lever | Workday | Amazon\nGoogle | Apple | Microsoft | Meta | Tesla\nAshby | iCIMS | TikTok | Netflix | Two Sigma"]
    SCRAPE --> FRESH["Filter: remove stale postings"]
    FRESH --> EXCLUDE["Filter: exclude intern, staff+,\nmanagement, director, VP, PhD"]
    EXCLUDE --> LOCATION["Filter: US-based roles only"]
    LOCATION --> SWE["Filter: SWE-relevant titles only"]
    SWE --> DEDUP{"Already\nin DB?"}
    DEDUP -- Yes --> SKIP([Skip])
    DEDUP -- No --> FETCHJD["Fetch descriptions\n(Playwright scrapers only,\nAPI scrapers already have JDs)"]
    FETCHJD --> STAGE1{"Stage 1:\nTitle rules"}
    STAGE1 -- "L4/L3" --> LEVEL["Level assigned\n(L3/L4)"]
    STAGE1 -- Ambiguous --> STAGE2{"Stage 2:\nDescription signals\n(YOE, keywords)"}
    STAGE2 -- "L4/L3" --> LEVEL
    STAGE2 -- Ambiguous --> GEMINI["Stage 3: Gemini 2.5 Flash\n4-way classify in batches of 50"]
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

    SCANSTART(["Every 5 min"]) --> QUERYL4["Query: level=L4/L3_OR_L4\nAND notified=false"]
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

## Classification Pipeline

Every scraped job passes through pre-filters and then a three-stage classification pipeline to determine its level. Jobs that fail any pre-filter gate are silently dropped.

### Pre-Filters (`JobTitleFilter`)

| Filter | Logic | Example drops |
|--------|-------|---------------|
| **Freshness** | Reject postings older than 30 days (by `postedDate`). Jobs with no date pass through. | Stale re-posts |
| **Exclude keywords** | Drop titles containing senior, sr., staff, principal, lead, manager, director, VP, intern, PhD, frontend, mobile, iOS, Android, embedded, security | "Senior SWE", "iOS Engineer", "Staff Platform Engineer" |
| **US location** | Keep only US-based roles. Checks state abbreviations, city names, "United States", "Remote". Rejects known non-US patterns (Canada, UK, India, Singapore, etc.) | "London, UK", "Bangalore, India" |
| **SWE relevance** | Require the title to match SWE-related keywords: software engineer, SDE, developer, backend, fullstack, platform, infrastructure, etc. | "Product Manager", "Data Analyst", "Hardware Engineer" |

### Stage 1 — Title Rules (`JobTitleFilter.autoClassifyLevel`)

Zero-cost regex classification on the job title alone:

- **L4 pattern** (`FilterKeywords.L4_PATTERN`): Matches "SWE II", "SDE 2", "Software Engineer II", "Engineer 2", etc.
- **L3 pattern** (`FilterKeywords.L3_PATTERN`): Matches "SWE I" (but not "II"), "SDE 1", "Software Engineer I", etc.
- **L3 title keywords** (`SignalExtractor.L3_TITLE_KEYWORDS`): Title contains "new grad", "junior", "entry level", "entry-level", "university", "graduate", "college" → L3
- Returns `null` (ambiguous) if none match → falls through to Stage 2

### Stage 2 — Description Signals (`SignalExtractor.inferLevelFromDescription`)

Parses the job description locally — no API call:

- **YOE parsing** (`YOE_PATTERN`): Regex extracts years-of-experience requirements ("3+ years", "2-5 years", etc.)
  - 2–5 YOE → **L4** (classified locally)
  - 0–1 YOE → deferred to Gemini (L3 signal, but too ambiguous to auto-classify)
- L3 keywords in descriptions ("new grad", "entry level", etc.) are extracted as `Signal` records for Gemini prompts but **not** used for local classification — this avoids false L3 classifications where "university" means campus location or "graduate" means degree requirement
- Returns `null` if no strong L4 signal → falls through to Stage 3

### Stage 3 — Gemini LLM (`JobClassifier` + `GeminiClient`)

Remaining ambiguous jobs are sent to **Gemini 2.5 Flash** for 4-way classification:

- **Batching**: Jobs grouped into batches of 50
- **Prompt**: Includes job title, company, and structured `Signal` records — each signal contains a keyword match with a ~200-char context snippet extracted by `SignalExtractor` from 12 consolidated keywords (YOE terms, academic signals, new-grad indicators)
- **Output**: Each job classified as **L3** / **L4** / **L3_OR_L4** / **OTHER**
  - L4 and L3_OR_L4 → mid-level notifications (primary recipients)
  - L3 → entry-level notifications (separate recipient list)
  - OTHER → stored but not notified

### Retry & Fallback

- Gemini API failures increment a `classificationFailures` counter on the job
- Failed jobs are retried on subsequent poll cycles
- After **3 consecutive failures** → auto-approved as **L4** (mid-level) to avoid silent drops

### Stage Distribution (observed, Apr 2026)

| Stage | Jobs | Share |
|-------|------|-------|
| Stage 1 — title rules | 56 | ~10% |
| Stage 2 — description signals | 106 | ~20% |
| Stage 3 — Gemini LLM | 379 | ~70% |
| **Total** | **541** over 315 poll cycles | |

## Supported Platforms

| Platform | Method | Companies |
|----------|--------|-----------|
| **Greenhouse** (77) | JSON API | Stripe, Airbnb, Cloudflare, Datadog, Twilio, Figma, Discord, Coinbase, Robinhood, Pinterest, Dropbox, DoorDash, Instacart, Databricks, MongoDB, Elastic, GitLab, Roblox, Unity, Lyft, Block, Anthropic, Twitch, Okta, Duolingo, LinkedIn, GoDaddy, Epic Games, Roku, Reddit, Squarespace, Groupon, Yext, Thumbtack, Pure Storage, Lucid Motors, Jane Street, Nextdoor, SoFi, Coursera, Samsara, Verkada, Waymo, Scale AI, Brex, Rubrik, Applied Intuition, The Trade Desk, Lucid Software, Tower Research Capital, Geneva Trading, Bill.com, Qualtrics, ZipRecruiter, IXL Learning, Akuna Capital, Point72, Instabase, Chime, Otter.ai, Flexport, Affirm, Coupang, Ripple, Oscar, Aquatic Capital Management, Glean, Smartsheet, StubHub, IMC Trading, Nuro, Optiver, Appian, DRW, Jump Trading, Airtable, Hudson River Trading |
| **Workday** (33) | JSON API | NVIDIA, Salesforce, Intel, Mastercard, Walmart, Adobe, Cisco, PayPal, Snap, Broadcom, Visa, Dell, Micron, Zoom, Equinix, NXP, IQVIA, Slack, Proofpoint, Abbott, Blue Origin, Cadence, Capital One, Cox, CrowdStrike, HPE, Travelers, Applied Materials, Morgan Stanley, Genentech, GEICO, BlackRock, Bloomberg |
| **Lever** (8) | JSON API | Spotify, Palantir, Plaid, Veeva, Zoox, Quantcast, Belvedere Trading, WeRide |
| **Ashby** (5) | JSON API | Whatnot, Notion, Confluent, OpenAI, Snowflake |
| **iCIMS** (1) | Playwright | Uber |
| **Amazon** | JSON API | Amazon |
| **Google** | Playwright | Google |
| **Apple** | Playwright | Apple |
| **Microsoft** | JSON API (PCSX) | Microsoft |
| **Meta** | GraphQL API | Meta |
| **Tesla** | Playwright | Tesla |
| **TikTok** | Playwright | TikTok |
| **SmartRecruiters** (1) | JSON API | ServiceNow |
| **OracleCloud** (2) | JSON API | JP Morgan, Fortinet |
| **Netflix** | JSON API (Eightfold) | Netflix |
| **Two Sigma** | HTML scraping | Two Sigma |

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
│   ├── GreenhouseScraper.java              # Greenhouse JSON API (77 companies)
│   ├── IcimsScraper.java                   # iCIMS (Playwright)
│   ├── LeverScraper.java                   # Lever JSON API (8 companies)
│   ├── MetaScraper.java                    # Meta Careers GraphQL
│   ├── MicrosoftScraper.java               # Microsoft PCSX API
│   ├── NetflixScraper.java                 # Netflix Careers (Eightfold AI)
│   ├── OracleCloudScraper.java             # OracleCloud HCM API
│   ├── SmartRecruitersScraper.java         # SmartRecruiters API
│   ├── TeslaScraper.java                   # Tesla Careers (Playwright)
│   ├── TikTokScraper.java                  # TikTok Careers (Playwright)
│   ├── TwoSigmaScraper.java               # Two Sigma Careers (Avature HTML)
│   └── WorkdayScraper.java                 # Workday API (33 companies)
├── service/
│   ├── JobPollingService.java              # Main orchestrator (15-min cycle)
│   ├── NotificationService.java            # Alert scanner (5-min cycle)
│   ├── DailySummaryService.java            # Daily digest email
│   ├── JobCleanupService.java              # 90-day retention cleanup
│   ├── PipelineMetrics.java                # Micrometer counters/gauges
│   └── classification/
│       ├── ClassificationPipeline.java     # 3-stage orchestrator: title → description → Gemini
│       ├── ClassificationResult.java       # Gemini response + level map
│       ├── FilterKeywords.java             # Title patterns (L3/L4) and excluded keywords
│       ├── GeminiClient.java               # Gemini API client (prompt building + HTTP)
│       ├── JobClassifier.java              # Batch Gemini classification with retry
│       ├── JobTitleFilter.java             # Pre-filters + title-based auto level classification
│       ├── Signal.java                     # Record: keyword + snippet + source (TITLE/DESCRIPTION)
│       └── SignalExtractor.java            # Signal extraction + YOE parsing + description inference
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
