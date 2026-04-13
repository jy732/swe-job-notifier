# Incident: H2 Database Corruption from future.cancel(true)

**Date:** 2026-04-10 13:23 – 22:25+ (ongoing until recovery)
**Severity:** Critical — all DB operations failed for 9+ hours
**Impact:** 772 failed DB operations, all polls and notification scans non-functional since 13:23

## Timeline

- **12:26** — App restarted on `feature/entry-level-notifications` branch. Polls running normally.
- **13:20:44** — Workday cisco times out after 180s. `future.cancel(true)` fires but no corruption (thread was still in scrape phase).
- **13:23:44** — Workday micron times out after 180s. `future.cancel(true)` interrupts the thread.
  - But the thread had already finished scraping (1000 jobs) and was in the **Gemini classify + DB persist** phase.
  - Thread interrupt hit `JobClassifier` mid-sleep ("Thread interrupted while sleeping").
  - Interrupt propagated into H2 MVStore during an `INSERT` statement.
  - H2's file channel was corrupted mid-write: `MVStoreException: Reading from file failed at 1236624 (length -1), read 0, remaining 768`.
- **13:23:44+** — Every subsequent DB operation fails with "Reading from file failed" or "The database has been closed". 772 errors logged.

## Root Cause

```java
// JobPollingService.java, awaitResult()
future.cancel(true);  // ← sends Thread.interrupt() to the worker thread
```

`future.cancel(true)` interrupts the thread regardless of what it's doing. The 180s company timeout is a safety net for slow scrapers, but the `processCompany()` pipeline runs scrape → filter → classify → persist **all on the same thread**. When the timeout fires late (after scraping finishes), the interrupt hits the DB write phase.

H2's MVStore uses `FileChannel` for I/O. A thread interrupt during a `FileChannel.read()` or `FileChannel.write()` closes the channel (per Java NIO spec — `ClosedByInterruptException`), leaving the `.mv.db` file with a half-written page. Once corrupted, **every subsequent operation fails** — the damage is permanent until recovery.

### Why micron and not cisco?

Both timed out at 180s. Cisco's thread was still scraping when cancelled — the interrupt killed Playwright (harmless). Micron's thread had already finished scraping 1000 jobs and had moved into classification/persist — the interrupt killed H2.

## Fix

```java
// Before (dangerous)
future.cancel(true);

// After (safe)
future.cancel(false);
```

`cancel(false)` marks the Future as cancelled without sending a thread interrupt. The timed-out thread finishes naturally — scrapers have their own Playwright page timeouts so they won't hang forever. The result is discarded either way (we return `CompanyResult.EMPTY`).

## Recovery

1. Stop the app (release H2 file lock)
2. Run `org.h2.tools.Recover` to extract data from corrupted `.mv.db` into a SQL script
3. Delete corrupted `.mv.db`, rebuild from the recovery script via `org.h2.tools.RunScript`
4. Restart the app

## Lessons

- **Never interrupt threads that do file I/O.** Java NIO `FileChannel` operations throw `ClosedByInterruptException` on interrupt, which permanently closes the channel.
- **H2 MVStore has no self-healing.** A single corrupted page makes the entire database unreadable until manual recovery.
- **Timeout boundaries should match execution phases.** The 180s timeout was meant for scraping, but it could fire during any phase of the pipeline.
