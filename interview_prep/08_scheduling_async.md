# 08 — Scheduling & Async: @Scheduled, @Async, CompletableFuture

> **Project anchor:** VaadVivaad runs two cron jobs — a 6 AM re-scrape and an 8 AM
> hearing-reminder — both defined in `NotificationScheduler.java`. There is no `@Async`
> usage yet, but understanding it is critical for any senior Java interview.
>
> **Node background:** You know `setInterval`, `node-cron`, `Promise`, `async/await`,
> and worker threads. This guide maps every Java concept back to that mental model.

---

## 1. Why Scheduled Tasks Exist at All

In Node you might write:

```js
// node-cron
const cron = require('node-cron');

cron.schedule('0 8 * * *', () => {
  sendHearingReminders();
});
```

Spring does the same thing, but as a first-class annotation. No third-party library,
no process manager, no separate worker file. Just annotate a method:

```java
@Scheduled(cron = "0 0 8 * * *")
public void sendHearingReminders() {
    // runs every day at 8:00 AM
}
```

The philosophy: scheduling is an infrastructural concern. Spring owns it. You declare
intent; the framework handles threads, timers, and lifecycle.

---

## 2. The Entry Ticket: @EnableScheduling

Spring does not scan for `@Scheduled` methods unless you explicitly turn on the
scheduler. VaadVivaad does this at the application root:

```java
// VaadVivaadApplication.java
@SpringBootApplication
@EnableScheduling   // <-- enables scheduled task scanning
@EnableCaching
public class VaadVivaadApplication {
    public static void main(String[] args) {
        SpringApplication.run(VaadVivaadApplication.class, args);
    }
}
```

Without `@EnableScheduling`, every `@Scheduled` annotation in the codebase is silently
ignored. This is a common beginner mistake — the method just never runs and there is no
error message.

**Node analogy:** It is like having `cron.schedule(...)` calls written but forgetting
to call `require('node-cron')` and start the process. The code exists; the scheduler
just never wakes up.

---

## 3. Three Scheduling Types

### 3a. fixedRate — "Every N milliseconds, start a new run"

```java
@Scheduled(fixedRate = 5000)   // every 5 seconds, wall-clock
public void pollForUpdates() { ... }
```

The clock starts when the application boots. Every 5 seconds a new execution begins,
**regardless of whether the previous one is still running**. If execution takes 6
seconds, you will have overlapping runs.

**Node analogy:** `setInterval(fn, 5000)` — the interval starts ticking immediately,
does not wait for `fn` to complete.

### 3b. fixedDelay — "Wait N milliseconds AFTER the last run finishes"

```java
@Scheduled(fixedDelay = 5000)  // 5 seconds after previous run completes
public void scrapeNextCase() { ... }
```

The 5-second countdown only begins after the method returns (or throws). This naturally
prevents overlap. If one run takes 10 seconds, the next starts 15 seconds after the
first began.

**Node analogy:** Recursive `setTimeout`:

```js
function poll() {
  doWork().finally(() => setTimeout(poll, 5000));
}
poll();
```

### 3c. cron — "At a specific calendar time"

```java
@Scheduled(cron = "0 0 8 * * *")  // 8:00:00 AM, every day
public void sendReminders() { ... }
```

Cron expressions give you calendar-based precision: time of day, day of week, day of
month. This is what VaadVivaad uses for both jobs.

**Comparison table:**

| Type | Overlap risk | Use when |
|------|-------------|---------|
| `fixedRate` | Yes | Polling at high frequency, short tasks |
| `fixedDelay` | No | Sequential tasks, one-at-a-time |
| `cron` | Yes (like fixedRate) | Calendar-based: daily, weekly, end-of-month |

---

## 4. Cron Expression Deep Dive

### Spring's 6-Field Format

Spring uses a **6-field** cron expression (standard Unix cron uses 5 fields, omitting
seconds). The fields left to right:

```
┌─────────────── second        (0–59)
│ ┌───────────── minute        (0–59)
│ │ ┌─────────── hour          (0–23)
│ │ │ ┌───────── day of month  (1–31)
│ │ │ │ ┌─────── month         (1–12 or JAN–DEC)
│ │ │ │ │ ┌───── day of week   (0–7 or SUN–SAT; 0 and 7 = Sunday)
│ │ │ │ │ │
0 0 8 * * *
```

### Decoding VaadVivaad's Two Cron Expressions

**Job 1 — 6 AM Re-scrape:**

```java
@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
public void reScrapeTrackedCases() { ... }
```

| Field | Value | Meaning |
|-------|-------|---------|
| second | 0 | at the 0th second |
| minute | 0 | at the 0th minute |
| hour | 6 | at 6 AM |
| day of month | * | every day |
| month | * | every month |
| day of week | * | every day of the week |

Result: fires at `06:00:00` IST, every day, all year.

Notice `zone = "Asia/Kolkata"`. Without it, Spring uses the JVM's default timezone
which on cloud hosts is typically UTC. A job meant to run at 6 AM IST would fire at
12:30 AM IST — you'd wake up to errors instead of sleeping. Always specify the zone
for business-time jobs.

**Job 2 — 8 AM Reminders:**

```java
@Scheduled(cron = "0 0 8 * * *")
public void sendHearingReminders() { ... }
```

Same pattern, fires at `08:00:00`. No `zone` specified — should be added for
production consistency.

### Special Characters

| Char | Meaning | Example |
|------|---------|---------|
| `*` | Any value | `* * * * * *` = every second |
| `?` | No specific value (day fields only) | `0 0 8 * * ?` |
| `-` | Range | `0 0 9-17 * * *` = 9 AM through 5 PM |
| `/` | Increment | `0 */15 * * * *` = every 15 minutes |
| `L` | Last | `0 0 0 L * *` = midnight on last day of month |
| `W` | Nearest weekday | `0 0 8 15W * *` = 8 AM nearest weekday to 15th |
| `#` | Nth weekday | `0 0 8 * * 2#1` = 8 AM first Monday of month |

### Common Real-World Expressions

```
0 0 0 * * *          Every day at midnight
0 0 8 * * MON-FRI    8 AM on weekdays only
0 30 9 * * *         9:30 AM every day
0 0 0 1 * *          Midnight on the 1st of every month
0 0 2 * * SUN        2 AM every Sunday (batch jobs, DB backups)
0 */30 * * * *       Every 30 minutes
0 0 8 * * 2#1        8 AM, first Tuesday of every month (board reports)
0 0 23 L * *         11 PM on last day of month (billing cutoff)
```

### How to Test Cron Expressions

1. **Online:** [crontab.guru](https://crontab.guru) — note it is 5-field (no seconds).
   For Spring's 6-field, use [freeformatter.com/cron-expression-generator-quartz.html](https://www.freeformatter.com/cron-expression-generator-quartz.html).

2. **In code (unit test):**

```java
import org.springframework.scheduling.support.CronExpression;

CronExpression expr = CronExpression.parse("0 0 8 * * *");
LocalDateTime next = expr.next(LocalDateTime.now());
System.out.println("Next fire: " + next);
```

3. **Actuator endpoint** (with `spring-boot-actuator`): `/actuator/scheduledtasks`
   lists all registered tasks and their next execution time.

---

## 5. VaadVivaad's Scheduler Logic — Reading the Real Code

```java
// NotificationScheduler.java — the complete class at a glance

@Component
public class NotificationScheduler {

    // --- JOB 1: 6 AM ---
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void reScrapeTrackedCases() {
        List<String> trackedCnrNumbers =
            subscriptionRepository.findAllDistinctCnrNumbers();

        for (String cnrNumber : trackedCnrNumbers) {
            try {
                scraperService.scrapeOrRefresh(cnrNumber);   // hits eCourts
                Thread.sleep(2000);  // polite delay — don't hammer the site
            } catch (ScraperException e) {
                log.warn("Re-scrape failed for CNR {}: {}", cnrNumber, e.getMessage());
                // continue — don't abort the whole job for one failure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // restore interrupt flag
                log.error("Re-scrape job interrupted");
                break;
            }
        }
    }

    // --- JOB 2: 8 AM ---
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void sendHearingReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<Hearing> tomorrowsHearings =
            hearingRepository.findByNextHearingDate(tomorrow);

        for (Hearing hearing : tomorrowsHearings) {
            processHearing(hearing);  // publishes HearingReminderEvent to RabbitMQ
        }
    }
}
```

### Why Two Separate Jobs Instead of One?

The comment in the code explains it well, but let's unpack the engineering reasoning:

**Single Responsibility Principle at the job level.** Scraping and notifying are
different operations with different failure modes, different dependencies, and different
recovery strategies.

**Failure isolation.** If the 6 AM scrape fails for half the cases (eCourts is down),
the 8 AM reminder job still runs. It sends reminders based on yesterday's data, which
is still correct for most cases. If both jobs were one, a scrape failure would abort
the entire reminder flow.

**Independent retry logic.** The scrape job catches `ScraperException` and continues.
The reminder job has its own error handling. Merging them means one error strategy must
serve two very different operations.

**Different transactional needs.** The scrape job calls `ScraperService.scrapeOrRefresh`
which is `@Transactional` (read-write). The reminder job is `@Transactional(readOnly=true)`.
These cannot be the same transaction type.

**The 2-hour gap is intentional.** Fresh data from 6 AM is available for the 8 AM job.
The sequence is: scrape → persist → (wait 2 hours) → read fresh data → send reminders.
This is a simple, reliable pipeline that does not require any coordination mechanism.

### Why `Thread.sleep(2000)` in the Scrape Loop?

Web scraping without rate limiting is rude and often illegal. eCourts is a government
portal with no public API. The 2-second delay between cases prevents flooding the
server with requests. In production this should be externalized to configuration:

```yaml
vaadvivaad:
  scraper:
    delay-between-requests-ms: 2000
```

### Why `Thread.currentThread().interrupt()` After `InterruptedException`?

This is a Java idiom that confuses Node developers. When you catch `InterruptedException`,
Java clears the thread's interrupt flag. If you swallow the exception and do nothing,
any upstream code that checks `Thread.interrupted()` will miss the signal.

Restoring the flag with `Thread.currentThread().interrupt()` is the correct pattern:
it marks the thread as interrupted so the scheduler can clean it up properly during
shutdown.

---

## 6. Thread Pool for @Scheduled — The Default Trap

This is the most important architectural detail interviewers ask about.

By default, Spring's scheduler runs on a **single-threaded** scheduler thread pool
(pool size = 1). All `@Scheduled` methods share one thread. This means:

- If Job A (6 AM) is still running when Job B (8 AM) fires, Job B **waits**.
- If Job A hangs indefinitely, Job B never runs.

For VaadVivaad this is currently acceptable — the 6 AM job is expected to finish well
before 8 AM. But in systems with many jobs, this is a real problem.

### The Fix: Configure ThreadPoolTaskScheduler

```java
@Configuration
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);                          // 5 concurrent jobs
        scheduler.setThreadNamePrefix("vaad-scheduler-");  // logs show: vaad-scheduler-1
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
```

With pool size 5, up to 5 scheduled jobs can run concurrently. Logs will show thread
names like `vaad-scheduler-2` making it easy to trace which job is executing.

**Node analogy:** Node's single event loop can handle multiple `node-cron` tasks
concurrently because they are async (non-blocking). Java's default single-thread
scheduler blocks — a slow job literally stops other jobs from starting. The fix is
explicitly adding threads.

---

## 7. @Async — Running a Method in a Separate Thread Pool

`@Async` is the annotation equivalent of Node's `setImmediate()` or wrapping something
in a microtask. It tells Spring: "run this method in a separate thread, don't make
the caller wait."

```java
@Service
public class SomeService {

    @Async                          // runs in the async thread pool
    public void sendEmailAsync(String to, String body) {
        // this executes on a different thread
        emailClient.send(to, body);
    }
}
```

The caller continues immediately:

```java
someService.sendEmailAsync(user.getEmail(), message);  // returns instantly
// execution continues here without waiting for the email to send
```

**Node analogy:**

```js
// Node — non-blocking by default
await someService.sendEmail(email, message);  // you must await
// OR use setImmediate to fire-and-forget
setImmediate(() => emailClient.send(email, message));
```

In Java, everything is blocking by default. `@Async` is the explicit escape hatch.

### The Entry Ticket: @EnableAsync

Just like `@EnableScheduling`, async processing requires an opt-in:

```java
@SpringBootApplication
@EnableScheduling
@EnableAsync         // <-- enables @Async processing
@EnableCaching
public class VaadVivaadApplication { ... }
```

Without `@EnableAsync`, methods annotated with `@Async` execute **synchronously** on
the calling thread with no error — same silent failure as `@Scheduled` without
`@EnableScheduling`.

### @Async Return Types

```java
// Fire and forget — no result
@Async
public void sendNotification(String userId) { ... }

// Return a value eventually
@Async
public Future<String> processReport(UUID reportId) {
    return new AsyncResult<>("done");
}

// Modern — returns CompletableFuture (preferred)
@Async
public CompletableFuture<CaseResponse> fetchCaseAsync(String cnr) {
    CaseResponse result = scraperService.scrapeOrRefresh(cnr);
    return CompletableFuture.completedFuture(result);
}
```

---

## 8. CompletableFuture — Java's Promise

`CompletableFuture<T>` is Java's equivalent of `Promise<T>`. Every pattern you know
from Node Promises maps to a CompletableFuture method.

### Core Factory Methods

```java
// With a result (like new Promise(resolve => ...) that returns a value)
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return fetchSomeData();   // runs in ForkJoinPool.commonPool() by default
});

// No result (like new Promise(resolve => ...) that just does work)
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    sendEmail();
});

// With a custom executor (always prefer this in production)
ExecutorService myPool = Executors.newFixedThreadPool(10);
CompletableFuture<String> future = CompletableFuture.supplyAsync(
    () -> fetchSomeData(),
    myPool
);
```

### Node → Java Mapping

| Node (Promise) | Java (CompletableFuture) |
|----------------|--------------------------|
| `new Promise(resolve => resolve(val))` | `CompletableFuture.completedFuture(val)` |
| `Promise.resolve(fn())` | `CompletableFuture.supplyAsync(fn)` |
| `.then(result => transform(result))` | `.thenApply(result -> transform(result))` |
| `.then(result => asyncFn(result))` | `.thenCompose(result -> asyncFn(result))` |
| `.then(() => sideEffect())` | `.thenRun(() -> sideEffect())` |
| `.catch(err => handleErr(err))` | `.exceptionally(err -> handleErr(err))` |
| `Promise.all([p1, p2, p3])` | `CompletableFuture.allOf(f1, f2, f3)` |
| `Promise.any([p1, p2, p3])` | `CompletableFuture.anyOf(f1, f2, f3)` |

### Example: Scraping Multiple Cases in Parallel

In VaadVivaad's scrape job, cases are scraped sequentially with `Thread.sleep(2000)`.
If you wanted to scrape them in parallel (with your own thread pool to control
concurrency):

```java
// Parallel scrape with CompletableFuture
List<CompletableFuture<CourtCase>> futures = trackedCnrNumbers.stream()
    .map(cnr -> CompletableFuture.supplyAsync(
        () -> scraperService.scrapeOrRefresh(cnr),
        scraperExecutor  // custom thread pool, not common pool
    ))
    .toList();

// Wait for all to complete (like Promise.all)
CompletableFuture<Void> allDone = CompletableFuture.allOf(
    futures.toArray(new CompletableFuture[0])
);

allDone.join();  // blocks until all futures complete (or any throws)
```

**Node equivalent:**

```js
const futures = cnrNumbers.map(cnr =>
    scraperService.scrapeOrRefresh(cnr)
);
await Promise.all(futures);
```

### Chaining — thenApply vs thenCompose

```java
// thenApply — synchronous transform (like .then(x => x + 1))
CompletableFuture<String> result = CompletableFuture
    .supplyAsync(() -> fetchHtml(cnr))     // returns String
    .thenApply(html -> parser.parse(html)) // sync transform
    .thenApply(ParsedCaseData::cnrNumber); // another sync transform

// thenCompose — async chain (like .then(x => asyncFn(x)))
CompletableFuture<CaseResponse> result = CompletableFuture
    .supplyAsync(() -> fetchHtml(cnr))
    .thenCompose(html -> CompletableFuture.supplyAsync(
        () -> parser.parse(html), anotherPool
    ));
```

The rule: use `thenApply` when the transform is a normal synchronous function.
Use `thenCompose` when the transform itself returns a `CompletableFuture` (otherwise
you get `CompletableFuture<CompletableFuture<T>>` — same as `.then()` vs `.flatMap()`
in functional programming).

### Error Handling

```java
CompletableFuture<CourtCase> future = CompletableFuture
    .supplyAsync(() -> scraperService.scrapeOrRefresh(cnr))
    .exceptionally(ex -> {
        log.error("Scrape failed for {}: {}", cnr, ex.getMessage());
        return null;   // fallback value (can return a default CourtCase)
    });

// Or handle and rethrow
    .handle((result, ex) -> {
        if (ex != null) {
            log.error("Scrape failed", ex);
            throw new RuntimeException("Scrape failed", ex);
        }
        return result;
    });
```

**Node equivalent:**

```js
scrapeOrRefresh(cnr)
  .catch(err => {
    console.error('Scrape failed:', err.message);
    return null;
  });
```

---

## 9. @Async + @Transactional — The Most Common Trap

This is a senior-level question. The interaction is subtle and breaks silently.

### The Problem

```java
@Service
public class OuterService {

    @Autowired
    private InnerService innerService;

    @Transactional   // transaction T1 starts here
    public void doWork() {
        // ... some DB writes in transaction T1 ...

        innerService.doAsyncPart();  // @Async call
        // T1 is still open here
    }
}

@Service
public class InnerService {

    @Async
    @Transactional   // tries to join T1? NO — new thread = new transaction context
    public void doAsyncPart() {
        // This runs on a NEW thread
        // Spring's transaction context is thread-local
        // T1 from the caller is NOT visible here
        // This method gets its own transaction T2 (or no transaction)
    }
}
```

Spring's transaction context is stored in `ThreadLocal`. When `@Async` spawns a new
thread, that thread has an empty `ThreadLocal` — no transaction from the caller.
`@Transactional` on the async method starts a **fresh** transaction, completely
independent of the caller's transaction.

### Why This Matters in VaadVivaad

In `ScraperService.scrapeOrRefresh`, we save a hearing and then publish a RabbitMQ
message — all inside `@Transactional`. The hearing ID is used in the message. If that
method were made async, the message could be published before the DB transaction
commits — the consumer fetches a hearing that does not exist yet. This is a real race
condition.

The code comment in `ScraperService.java` acknowledges this:

```java
/*
 * Publish summary request AFTER hearing is saved and has an ID.
 * WHY after save? Because the consumer fetches the hearing by ID.
 * Publishing before save means the consumer might fetch before
 * the transaction commits — race condition.
 */
```

### The Correct Pattern

If you need async + transactional, start the transaction inside the async method:

```java
@Async("scraperExecutor")
public void processAsync(String cnrNumber) {
    // transaction starts here, on THIS thread
    scrapeAndSave(cnrNumber);  // @Transactional — commits on THIS thread
}
```

Never assume a transaction propagates across thread boundaries.

---

## 10. Thread Pool Configuration for @Async

The default async executor in Spring uses `SimpleAsyncTaskExecutor`, which creates a
**new thread for every @Async call**. This is terrible in production — unbounded thread
creation under load causes OutOfMemoryError.

Always configure an explicit `ThreadPoolTaskExecutor`:

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("scraperExecutor")
    public Executor scraperExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);        // always-alive threads
        executor.setMaxPoolSize(10);        // max threads under load
        executor.setQueueCapacity(100);     // queue before rejecting
        executor.setThreadNamePrefix("vaad-scraper-");  // debug gold
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy()  // don't drop work
        );
        executor.initialize();
        return executor;
    }

    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("vaad-notify-");
        executor.initialize();
        return executor;
    }
}
```

Usage — target a specific executor by name:

```java
@Async("notificationExecutor")   // uses the named bean
public void sendEmailAsync(...) { ... }
```

If `@Async` has no name, Spring looks for a bean named `taskExecutor` (or the default
`SimpleAsyncTaskExecutor` if none exists).

### Why Thread Names Matter

```
2024-01-15 08:00:01 [vaad-scheduler-1] INFO  NotificationScheduler - Scheduler running
2024-01-15 08:00:02 [vaad-notify-3]    INFO  EmailService - Sending email to user@xyz.com
2024-01-15 08:00:02 [vaad-notify-1]    INFO  EmailService - Sending email to user@abc.com
```

Without named threads you get `pool-1-thread-3` which tells you nothing. Named threads
tell you exactly which pool is doing what, which is essential when debugging a 3 AM
production incident.

### Thread Pool Sizing Heuristics

For CPU-bound work (parsing, computation):
```
pool size ≈ number of CPU cores
```

For IO-bound work (HTTP calls, DB queries — like VaadVivaad's scraper):
```
pool size ≈ number of CPU cores × (1 + wait-time / service-time)
```

If each eCourts request takes 500ms and processing takes 50ms, that ratio is 10, so
`cores × 11` is a reasonable starting point. Tune with load testing.

---

## 11. @Scheduled + @Transactional — How VaadVivaad Uses It

The 8 AM job:

```java
@Scheduled(cron = "0 0 8 * * *")
@Transactional(readOnly = true)   // transaction wraps the entire method
public void sendHearingReminders() {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    List<Hearing> tomorrowsHearings =
        hearingRepository.findByNextHearingDate(tomorrow);   // inside transaction

    for (Hearing hearing : tomorrowsHearings) {
        processHearing(hearing);   // accesses hearing.getCourtCase() — lazy load OK
    }
}                                  // transaction commits (read-only, so no flush)
```

`@Transactional(readOnly = true)` tells Hibernate it can skip dirty-checking (no need
to compare entity state at commit time) and tells the DB driver it can use read replicas.
For a job that only reads data and publishes events, this is exactly right.

The transaction keeps the Hibernate session open for the entire loop. Without it, lazy-
loaded associations like `hearing.getCourtCase()` would throw `LazyInitializationException`
when accessed outside the session.

**Pattern:** Scheduler calls service, service method carries the `@Transactional`.

```java
// Alternative — let the service own the transaction (cleaner)
@Scheduled(cron = "0 0 8 * * *")
public void sendHearingReminders() {
    hearingReminderService.processRemindersForDate(LocalDate.now().plusDays(1));
    // ^^^^ the service method is @Transactional
}
```

This is architecturally cleaner: the scheduler is responsible for timing, the service
is responsible for business logic and data access.

---

## 12. Preventing Overlapping Executions

### The Problem

VaadVivaad's 6 AM job iterates over potentially hundreds of court cases. If eCourts
is slow and the job takes longer than 24 hours (pathological, but possible in theory),
the next day's 6 AM job fires and you have two jobs simultaneously hammering eCourts.

More realistic: you deploy two instances of VaadVivaad for high availability. Both
instances start their 6 AM jobs simultaneously. Every case gets scraped twice. Every
user gets two reminder notifications.

### fixedDelay vs cron

`fixedDelay` naturally prevents overlap — the next run waits for the previous to
finish. But `fixedDelay` gives you `now + N ms` timing, not calendar time. You cannot
say "run at 6 AM IST every day" with `fixedDelay`.

`cron` is like `fixedRate` — it fires at the wall-clock time regardless of the previous
run. Two instances means two executions.

### Solution: ShedLock

ShedLock is the standard library for this problem. It uses a database table (one row
per job) as a distributed lock. Before a job runs, it tries to acquire the lock
(a database row). Only one instance succeeds. The other sees the lock taken and skips.

Add to `pom.xml`:

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>5.10.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-jdbc-template</artifactId>
    <version>5.10.0</version>
</dependency>
```

Flyway migration to create the lock table:

```sql
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

Configuration:

```java
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")  // lock expires after 10 min
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()   // use DB clock, not server clock
                .build()
        );
    }
}
```

Usage on the scheduler:

```java
@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
@SchedulerLock(
    name = "reScrapeTrackedCases",
    lockAtLeastFor = "PT5M",    // hold lock for at least 5 min (prevents rapid retry)
    lockAtMostFor  = "PT2H"     // release lock after 2 hours even if job is stuck
)
public void reScrapeTrackedCases() { ... }
```

With ShedLock, adding a second VaadVivaad instance for horizontal scaling is safe.
Only one instance runs the job.

**This is a VaadVivaad improvement item** — not currently implemented but should be
added before horizontal scaling.

---

## 13. Graceful Shutdown

When a K8s pod is evicted, or you roll a deployment, the app receives `SIGTERM`.
Without graceful shutdown configuration, the JVM exits immediately — mid-scrape,
mid-transaction.

Spring Boot supports graceful shutdown:

```yaml
# application.yml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

With this configuration, when the app receives `SIGTERM`:
1. Spring stops accepting new HTTP requests.
2. Spring stops accepting new scheduled job executions.
3. Spring waits up to 30 seconds for in-flight requests and running scheduled tasks
   to complete.
4. Only then does the JVM exit.

For VaadVivaad's scrape job, this means a case currently being scraped is allowed
to finish. The next case in the loop is not started. The DB transaction commits cleanly.

**Node equivalent:** `process.on('SIGTERM', () => server.close(callback))` — you
explicitly drain existing connections. Spring handles this automatically with the
`graceful` shutdown mode.

**The `ThreadPoolTaskScheduler` config matters here too:**

```java
scheduler.setWaitForTasksToCompleteOnShutdown(true);
scheduler.setAwaitTerminationSeconds(30);
```

Without this, the scheduler thread pool terminates immediately even with graceful
shutdown enabled.

---

## 14. Quartz Scheduler — The Enterprise Alternative

Spring's `@Scheduled` is excellent for simple cron jobs. Quartz is for complex,
persistent job orchestration.

| Feature | @Scheduled | Quartz |
|---------|-----------|--------|
| Setup | Just `@EnableScheduling` | Requires config, dependency |
| Job persistence | No — lost on restart | Yes — stored in DB |
| Clustering | Requires ShedLock | Built-in |
| Misfire handling | None | Configurable strategies |
| Dynamic scheduling | No — fixed at startup | Yes — create/delete jobs at runtime |
| Monitoring | Actuator endpoint | Admin UI (Quartz Monitor) |
| Use case | Simple daily/weekly jobs | Complex job pipelines, retries |

**When to choose Quartz over @Scheduled:**
- Jobs must not be missed (critical billing jobs, compliance reports)
- You need to schedule jobs dynamically based on user input
- Multi-instance deployment without ShedLock already in use
- Jobs have complex retry and misfire strategies
- You need a job history/audit trail

For VaadVivaad at current scale, `@Scheduled` + ShedLock is the right choice. Quartz
would be over-engineering.

---

## 15. Node → Java Comparison Reference

### Scheduling

```js
// Node — node-cron
const cron = require('node-cron');
cron.schedule('0 8 * * *', () => {   // 5-field: min hour day month weekday
    sendReminders();
});
```

```java
// Spring — annotation-driven, 6-field (includes seconds)
@Scheduled(cron = "0 0 8 * * *")
public void sendReminders() { }
```

### Parallel Async Work

```js
// Node — Promise.all
const results = await Promise.all(
    cnrNumbers.map(cnr => scrapeCase(cnr))
);
```

```java
// Java — CompletableFuture.allOf
List<CompletableFuture<CourtCase>> futures = cnrNumbers.stream()
    .map(cnr -> CompletableFuture.supplyAsync(
        () -> scraperService.scrapeOrRefresh(cnr), executor))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

List<CourtCase> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

### Background Work

```js
// Node — setImmediate (next iteration of event loop)
setImmediate(() => sendEmail(user.email, message));
// or
process.nextTick(() => publishEvent(event));
```

```java
// Java — @Async (runs on a thread pool, not inline)
@Async("notificationExecutor")
public void sendEmailAsync(String email, String message) {
    emailClient.send(email, message);
}
```

### Worker Threads vs @Async

```js
// Node — worker_threads for CPU-intensive work
const { Worker } = require('worker_threads');
const worker = new Worker('./parser.js', { workerData: { html } });
worker.on('message', parsedData => processData(parsedData));
```

```java
// Java — @Async with a thread pool for any blocking/CPU work
@Async("parserExecutor")
public CompletableFuture<ParsedCaseData> parseAsync(String html) {
    ParsedCaseData result = htmlParser.parse(html);  // runs on parserExecutor thread
    return CompletableFuture.completedFuture(result);
}
```

### Queue-Based Work Distribution

```js
// Node — Bull queue (Redis-backed)
const queue = new Bull('notifications');
queue.add({ userId, hearingId });
queue.process(async (job) => {
    await sendNotification(job.data);
});
```

```java
// Java — @Scheduled triggers, RabbitMQ distributes
// (VaadVivaad's actual approach)
@Scheduled(cron = "0 0 8 * * *")
public void sendHearingReminders() {
    // publishes to RabbitMQ exchange
    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
}

// Consumer in another class (or another service entirely)
@RabbitListener(queues = HEARING_REMINDER_QUEUE)
public void onHearingReminder(HearingReminderEvent event) {
    notificationService.send(event);
}
```

---

## 16. Interview Q&A — 10 Senior-Level Questions

**Q1: Spring's `@Scheduled` uses a single-threaded scheduler by default. What's the
risk and how do you fix it?**

A: If one scheduled job runs long, it blocks all other scheduled jobs because they
share one thread. Fix: define a `ThreadPoolTaskScheduler` bean with `poolSize > 1`.
In VaadVivaad, the 6 AM scrape is a long-running job. If it were to run past 8 AM
without a multi-threaded scheduler, the hearing reminder job would queue behind it.

**Q2: What's the difference between `fixedRate` and `fixedDelay`?**

A: `fixedRate` fires every N ms from the last fire time — does not wait for execution
to finish, can overlap. `fixedDelay` waits N ms after the previous execution completes
— cannot overlap by design. For long-running jobs, `fixedDelay` is safer. For time-
critical polling, `fixedRate` is appropriate.

**Q3: VaadVivaad has two cron jobs at 6 AM and 8 AM. What happens if two instances of
the app are deployed?**

A: Both instances run both jobs. Every case gets scraped twice. Every user gets two
notifications. The fix is ShedLock — a distributed lock backed by a DB table. Only
one instance acquires the lock and runs the job; the other skips.

**Q4: Does `@Async` propagate the caller's transaction?**

A: No. Spring's transaction context is stored in `ThreadLocal`. `@Async` runs the
method on a new thread with an empty `ThreadLocal`. The async method either starts its
own transaction (`@Transactional` on the async method creates a new one) or runs with
no transaction. This is one of the most common Spring bugs: developers assume the
async code participates in the parent transaction.

**Q5: What is the difference between `thenApply` and `thenCompose` on
CompletableFuture?**

A: `thenApply` takes a synchronous function `T -> U` and returns
`CompletableFuture<U>`. `thenCompose` takes a function `T -> CompletableFuture<U>`
and returns `CompletableFuture<U>` (flattened). Use `thenCompose` when the next step
is itself async — otherwise you get a nested `CompletableFuture<CompletableFuture<T>>`.
Same distinction as `.then(sync)` vs `.then(async)` chaining in JavaScript Promises.

**Q6: Why does Spring use 6 fields in cron expressions when standard Unix cron uses 5?**

A: Spring uses the Quartz cron format which adds a `seconds` field at the beginning.
Standard Unix cron (`* * * * *`) goes minute-hour-day-month-weekday. Spring/Quartz
(`* * * * * *`) goes second-minute-hour-day-month-weekday. This allows second-level
precision which Unix cron cannot express.

**Q7: VaadVivaad's 6 AM cron job has `zone = "Asia/Kolkata"` but the 8 AM job does
not. What problem could this cause in production?**

A: If the JVM's default timezone is UTC (common on cloud VMs and containers), the
8 AM job fires at 08:00 UTC, which is 13:30 IST. Users would receive hearing reminders
at 1:30 PM instead of 8 AM — too late to prepare for a hearing the next morning.
The `zone` attribute should be explicitly set on both jobs.

**Q8: Why configure `CallerRunsPolicy` as the rejected execution handler for the
async thread pool?**

A: When the thread pool is at capacity and the queue is full, Spring must either drop
the task or handle it. `CallerRunsPolicy` makes the calling thread execute the task
instead of rejecting it. This applies natural backpressure — the caller slows down,
preventing the system from dropping work. The alternative `AbortPolicy` throws
`RejectedExecutionException` which requires explicit handling. For VaadVivaad's
notification sending, dropping notifications silently is unacceptable.

**Q9: How does graceful shutdown interact with @Scheduled jobs?**

A: With `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase`,
Spring waits for in-progress scheduled jobs to complete before exiting. The scheduler
stops accepting new executions immediately (no new jobs fire). Existing running jobs
are given the configured timeout window to finish. This is critical for K8s where
pods are evicted during rolling deployments — without it, mid-scrape transactions are
rolled back, partial data is written.

**Q10: How would you handle a scenario where the 6 AM scrape sometimes takes 25 hours
because there are too many cases?**

A: Several approaches, applied in layers:
1. **Concurrency:** Run scrapes in parallel using `CompletableFuture.supplyAsync` with
   a bounded thread pool. 10 threads cuts 25 hours to ~2.5 hours.
2. **Pagination:** Process cases in batches with checkpointing so a restart resumes
   from where it left off.
3. **Prioritization:** Scrape cases with hearings in the next 7 days first. Cases
   with hearings 6 months away can be scraped less frequently.
4. **Incremental refresh:** Track `last_scraped_at` (VaadVivaad already does this).
   Only re-scrape cases that are actually stale.
5. **ShedLock with `lockAtMostFor`:** If the job hangs, release the lock after a
   configured maximum so the next day's job can still fire.

---

## Senior Differentiators

These are the details that separate someone who has read the docs from someone who
has felt the pain:

**1. The silent failure pattern.** `@Scheduled` without `@EnableScheduling` and
`@Async` without `@EnableAsync` produce no errors — the annotations are silently
ignored. In a code review, always check the main application class for these
annotations when you see scheduling/async code.

**2. Transaction and thread boundary are synonymous.** In Spring, transactions are
thread-local. Any time you cross a thread boundary — `@Async`, `CompletableFuture`,
`ExecutorService.submit` — you leave the transaction. Full stop. This is not obvious
from the code. You must internalize it.

**3. The single-thread scheduler is a latent bug.** Most applications with `@Scheduled`
never configure a `ThreadPoolTaskScheduler`. They work fine with 1-2 jobs but fail
silently (jobs start late or not at all) as more jobs are added. The correct default
for any production system is a pool of at least 5.

**4. ShedLock is not optional for multi-instance deployments.** If you deploy two
instances without it, you have a correctness bug from day one. Twice the scraping,
twice the notifications. This is a business correctness issue, not just a performance
issue.

**5. Thread naming is observability infrastructure.** `vaad-scheduler-1` tells you
more in a stack trace or a log than `pool-4-thread-7`. Thread naming is free.
Not doing it is pure laziness. In an interview, mentioning thread naming shows you
think about production operations, not just feature development.

**6. `CompletableFuture.join()` vs `.get()`.** Both block until completion. `.get()`
throws checked exceptions (`InterruptedException`, `ExecutionException`) that you must
handle. `.join()` throws unchecked `CompletionException`. In most application code,
`.join()` is cleaner because it doesn't litter the code with try-catch for exceptions
that can't meaningfully be handled at that call site.

**7. The cron timezone trap is real.** Docker containers and cloud VMs default to UTC.
A job meant for 6 AM local time runs at a seemingly random time. Always specify `zone`
on calendar-based cron jobs. This is the kind of production bug that happens on
day one of a cloud deployment and takes hours to diagnose.

**8. VaadVivaad's 2-second scrape delay is a design decision, not laziness.** Rate
limiting web scraping is the difference between a tool that runs for months and one
that gets your IP banned the first week. The delay should be configurable
(`@Value("${vaadvivaad.scraper.delay-ms:2000}"`). Hard-coding it is MVP debt.

**9. The outbox pattern for transactional messaging.** VaadVivaad publishes RabbitMQ
messages inside `@Transactional`. If the transaction rolls back after publishing, the
consumer gets a message referencing a non-existent hearing ID. The production-correct
solution is the transactional outbox pattern: write messages to a DB table in the same
transaction, then a separate poller publishes them to RabbitMQ. The code itself
acknowledges this as acceptable MVP-level risk.

**10. Quartz vs @Scheduled is a question about requirements, not preference.** If
someone asks "why not use Quartz?", the answer is: @Scheduled covers 90% of
scheduling needs with 10% of the complexity. Add Quartz when you need persistence
across restarts, dynamic job management, or complex misfire handling. Not before.
