# 04 — Transactions & Service Layer

> VaadVivaad · Spring Boot 3.4.4 / Java 21
> Senior interview prep: concepts feel logical, not mechanical.

---

## Table of Contents

1. [Why the Service Layer Exists](#1-why-the-service-layer-exists)
2. [@Transactional — How It Actually Works](#2-transactional--how-it-actually-works)
3. [Transaction Propagation](#3-transaction-propagation)
4. [Transaction Isolation Levels](#4-transaction-isolation-levels)
5. [readOnly = true](#5-readonly--true)
6. [The Self-Invocation Trap](#6-the-self-invocation-trap)
7. [Rollback Rules](#7-rollback-rules)
8. [The RabbitMQ + @Transactional Gap](#8-the-rabbitmq--transactional-gap)
9. [Service Layer Patterns in VaadVivaad](#9-service-layer-patterns-in-vaadvivaad)
10. [ApplicationEventPublisher](#10-applicationeventpublisher)
11. [@EventListener vs @TransactionalEventListener](#11-eventlistener-vs-transactionaleventlistener)
12. [Thin Controllers Pattern](#12-thin-controllers-pattern)
13. [DTOs — Why Not Return Entities](#13-dtos--why-not-return-entities)
14. [@Valid and the Validation Flow](#14-valid-and-the-validation-flow)
15. [Node → Java Comparison Table](#15-node--java-comparison-table)
16. [Senior Interview Q&A](#16-senior-interview-qa)
17. [Senior Differentiators](#17-senior-differentiators)

---

## 1. Why the Service Layer Exists

### The Node.js Starting Point

In Express, it's easy to put everything in a route handler:

```javascript
// The "everything in the route" anti-pattern — you've probably done this
app.post('/subscriptions', async (req, res) => {
  const { userId, caseId } = req.body;

  // Business rule check
  const exists = await db.query(
    'SELECT 1 FROM subscriptions WHERE user_id=$1 AND case_id=$2',
    [userId, caseId]
  );
  if (exists.rows.length > 0) {
    return res.status(409).json({ error: 'Already subscribed' });
  }

  // DB write
  const sub = await db.query(
    'INSERT INTO subscriptions (user_id, case_id) VALUES ($1, $2) RETURNING *',
    [userId, caseId]
  );

  // Publish to queue
  await channel.publish('subscriptions', JSON.stringify(sub.rows[0]));

  res.status(201).json(sub.rows[0]);
});
```

This works. For a weekend project. The problems emerge when:
- You need to call "subscribe logic" from somewhere else (a scheduler, a batch job, an admin endpoint)
- You need to test the business rule without spinning up an HTTP server
- A new developer has to understand what HTTP-level concerns are vs. what app-level concerns are

### The Java Spring Answer: Three Layers, Three Concerns

```
HTTP Request
     |
     v
[Controller]     ← HTTP adapter only. Parse request, call service, wrap response.
     |
     v
[Service]        ← ALL business logic lives here. @Transactional lives here.
     |
     v
[Repository]     ← Data access only. SQL/JPQL lives here. No business logic.
     |
     v
[Database]
```

The insight is that these are genuinely different jobs:

| Layer | Question it answers | VaadVivaad example |
|---|---|---|
| Controller | "What HTTP shape does this operation have?" | `POST /api/subscriptions`, returns 201 |
| Service | "What are the rules for subscribing?" | Can't subscribe twice, must fetch user and case first |
| Repository | "How do we store a subscription?" | `subscriptionRepository.save(subscription)` |

When business logic lives in the service layer, you can call it from a `@Scheduled` job, a message listener, an admin CLI, and a REST controller — all without duplicating code.

---

## 2. @Transactional — How It Actually Works

### The Mental Model: Spring Creates a Wrapper Around Your Class

When you annotate a class (or method) with `@Transactional`, Spring does **not** modify your class at all. Instead, at startup it creates a **proxy** — a dynamically generated subclass that wraps your bean. Every call to your bean goes through this proxy first.

The sequence, step by step:

```
Caller → Proxy.subscribe() → [open JDBC transaction]
                           → YourService.subscribe()  ← your actual code runs
                           → [success? commit. exception? rollback]
                           → return result to caller
```

This means `@Transactional` is not a thread lock. It is not magic. It is a wrapper that manages a JDBC connection's `autoCommit=false` state around your method.

### VaadVivaad Example — SubscriptionService

```java
// subscription/service/SubscriptionService.java

@Service
public class SubscriptionService {

    @Transactional  // <-- proxy intercepts this call
    public SubscriptionResponse subscribe(SubscriptionRequest request) {

        // 1. Fetch user (SELECT — inside the transaction)
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(...));

        // 2. Fetch court case (SELECT — same transaction)
        CourtCase courtCase = courtCaseRepository.findById(request.caseId())
                .orElseThrow(() -> new ResourceNotFoundException(...));

        // 3. Business rule: no duplicate subscriptions
        if (subscriptionRepository.existsByUserIdAndCourtCaseId(...)) {
            throw new IllegalArgumentException("Already subscribed...");
            // ^ RuntimeException → proxy will ROLLBACK
        }

        // 4. Save (INSERT — same transaction)
        Subscription saved = subscriptionRepository.save(subscription);

        // 5. Publish to RabbitMQ (outside transaction system — more on this below)
        rabbitTemplate.convertAndSend(..., event);

        return toResponse(saved, courtCase);
        // ^ method returns normally → proxy COMMITs the transaction
    }
}
```

### What the Proxy Actually Does (Pseudocode)

```java
// This is conceptually what Spring generates at runtime — you never write this
public class SubscriptionService$$SpringCGLIB$$0 extends SubscriptionService {

    @Override
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        TransactionStatus tx = transactionManager.getTransaction(txDefinition);
        try {
            SubscriptionResponse result = super.subscribe(request);  // your code
            transactionManager.commit(tx);
            return result;
        } catch (RuntimeException ex) {
            transactionManager.rollback(tx);
            throw ex;
        }
        // Checked exceptions do NOT trigger rollback by default (see section 7)
    }
}
```

### Key Facts to Know

- Spring uses **CGLIB** proxies by default for `@Service` beans (subclass-based)
- It uses **JDK dynamic proxies** for beans accessed through interfaces
- The proxy only intercepts **external calls** — calls from OUTSIDE the bean (this is why self-invocation is a trap; see section 6)
- One `@Transactional` method = one JDBC transaction = one database connection from the pool

---

## 3. Transaction Propagation

Propagation answers the question: **"What should happen to the transaction when a @Transactional method calls another @Transactional method?"**

This is relevant any time you have services calling other services.

### All Seven Propagation Types

| Propagation | Behavior | When to use |
|---|---|---|
| `REQUIRED` | Join existing transaction; create new if none exists | Default. Use for everything unless you have a specific reason |
| `REQUIRES_NEW` | Always create a new transaction; suspend the outer one | Audit logging that must persist even if the outer tx rolls back |
| `NESTED` | Create a savepoint within the outer transaction; can partially roll back | Complex multi-step operations with partial rollback points |
| `SUPPORTS` | Use existing transaction if present; run non-transactionally if not | Read-only helpers that work either way |
| `NOT_SUPPORTED` | Always run without a transaction; suspend outer if present | Reporting queries where you explicitly want no transaction |
| `MANDATORY` | Must join an existing transaction; throws if no transaction present | Internal-only helpers that should never be called without a tx |
| `NEVER` | Must run without a transaction; throws if a transaction is active | Non-transactional operations that would be corrupted by a tx |

### The Default (REQUIRED) — What VaadVivaad Uses

```java
@Transactional  // propagation = REQUIRED by default
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    // ...
    Subscription saved = subscriptionRepository.save(subscription);
    // subscriptionRepository.save() is also @Transactional(REQUIRED)
    // Since we're already in a transaction, it JOINS our transaction.
    // Same JDBC connection. Same commit/rollback unit.
}
```

### REQUIRES_NEW — The Most Important Non-Default

**Real-world scenario**: You're saving a subscription. You also want to write an audit log. The audit log should persist even if the subscription save fails.

```java
@Service
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String userId, String action) {
        // This runs in a SEPARATE transaction.
        // Even if the outer (subscription) transaction rolls back,
        // this audit record is committed independently.
        auditRepository.save(new AuditLog(userId, action, LocalDateTime.now()));
    }
}

@Service
public class SubscriptionService {

    @Transactional  // outer transaction
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        auditService.logAction(userId, "SUBSCRIBE_ATTEMPT");  // commits separately
        // ...
        subscriptionRepository.save(subscription);  // if THIS fails and rolls back,
        // the audit log is ALREADY committed. That's the point.
    }
}
```

**VaadVivaad does not currently use REQUIRES_NEW**, but if you added audit logging, this is exactly the pattern.

### NESTED — Savepoints

```java
@Transactional(propagation = Propagation.NESTED)
public void processOneBatch(List<Item> items) {
    // Creates a SAVEPOINT in the outer transaction.
    // If this fails, only this savepoint rolls back.
    // The outer transaction can catch the exception and continue.
}
```

Not all databases support savepoints equally well. PostgreSQL does. Use this when you want partial rollback within a larger operation.

---

## 4. Transaction Isolation Levels

### The Three Problems Isolation Levels Solve

Before the levels, understand WHY they exist. Three bad things can happen when two transactions run concurrently:

| Problem | What happens | Example |
|---|---|---|
| **Dirty Read** | Transaction A reads data written by B but B hasn't committed yet. If B rolls back, A read garbage. | A reads a balance of 1000. B was in the middle of a withdrawal and rolled back. The 1000 never existed. |
| **Non-Repeatable Read** | Transaction A reads a row. Transaction B updates and commits that row. A reads it again and gets a different value. | A queries a hearing date, gets March 15. B reschedules it to March 20 and commits. A re-queries, gets March 20. |
| **Phantom Read** | Transaction A runs a query returning 5 rows. B inserts new rows matching the criteria. A re-runs the same query, gets 7 rows. | A counts subscriptions for a case: 3. B adds 2 more. A re-counts: 5. |

### The Four Isolation Levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|---|---|---|---|---|
| `READ_UNCOMMITTED` | Possible | Possible | Possible | Highest |
| `READ_COMMITTED` | Prevented | Possible | Possible | High |
| `REPEATABLE_READ` | Prevented | Prevented | Possible | Medium |
| `SERIALIZABLE` | Prevented | Prevented | Prevented | Lowest |

**PostgreSQL default: READ_COMMITTED.** This is almost always what you want.

### How to Set Isolation Level in Spring

```java
// On a specific method:
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void criticalPriceUpdate() { ... }

// On a class (applies to all methods):
@Transactional(isolation = Isolation.READ_COMMITTED)
@Service
public class OrderService { ... }
```

### When to Change the Default

Most applications never need to change from READ_COMMITTED. You'd move to a higher level when:

- `REPEATABLE_READ`: Financial operations where you read a value, compute something based on it, then write. You need the value to not change mid-computation.
- `SERIALIZABLE`: Inventory reservation systems, seat booking — anywhere "check then act" must be atomic.

**VaadVivaad**: `READ_COMMITTED` is correct for all operations here. Court case data is not financial — a non-repeatable read on a hearing date is acceptable.

---

## 5. readOnly = true

### What It Is

```java
// CaseLookupService.java
@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) {
    CourtCase courtCase = courtCaseRepository.findByCnrNumber(cnrNumber)
        .orElseThrow(() -> new ResourceNotFoundException(...));

    List<Hearing> hearings = hearingRepository
        .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());

    return mapToResponse(courtCase, hearings);
}
```

### Three Things `readOnly = true` Actually Does

**1. Hibernate skips dirty checking.**
Normally at the end of every transaction, Hibernate compares every entity it loaded against its original snapshot ("dirty checking") to see if it needs to flush changes to the DB. This is O(n) work per entity. With `readOnly = true`, Hibernate skips this entirely — it knows you can't have modified anything.

**2. JDBC driver can optimize.**
Some JDBC drivers (PostgreSQL's included) use the `readOnly` hint to set the connection to read-only mode, which can skip write-ahead log overhead.

**3. Connection pool / read replicas.**
In production architectures with a primary + read replicas, some connection pools (PgBouncer, HikariCP with a routing datasource) can route `readOnly` transactions to read replicas automatically. This scales read traffic horizontally without any application code change.

### Pattern in VaadVivaad

All three read methods in `CaseLookupService` use `readOnly = true`:

```java
@Transactional(readOnly = true)  // lookupByCnr
@Transactional(readOnly = true)  // lookupById
@Transactional(readOnly = true)  // listCases
```

Write methods use plain `@Transactional`:

```java
@Transactional                   // createCase (INSERT)
```

This is the correct pattern. Always annotate your reads with `readOnly = true`. It is not optional pedantry — it is a real performance optimization.

### Why `AuthService.login` Uses readOnly = true

```java
// AuthService.java
@Transactional(readOnly = true)
public AuthResponse login(LoginRequest request) {
    authenticationManager.authenticate(...);
    User user = userRepository.findByEmail(request.email())...;
    String token = jwtService.generateToken(...);
    return new AuthResponse(...);
}
```

Login only reads from the database. No writes. `readOnly = true` is correct here. The JWT is generated in-memory — that's not a database write.

---

## 6. The Self-Invocation Trap

This is the number one senior Java gotcha. If you understand this, you've demonstrated real depth.

### The Setup

```java
@Service
public class NotificationScheduler {

    // Method A — NOT @Transactional
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void sendHearingReminders() {
        List<Hearing> hearings = hearingRepository.findByNextHearingDate(tomorrow);
        for (Hearing hearing : hearings) {
            processHearing(hearing);  // <-- calls a private method in the SAME class
        }
    }

    // Method B — private helper
    private void processHearing(Hearing hearing) {
        // ... accesses hearing.getCourtCase() — lazy loaded?
        // ... builds events, publishes to RabbitMQ
    }
}
```

`sendHearingReminders` calls `processHearing` directly. `processHearing` is private — it can't be `@Transactional` anyway (Spring's proxy can't intercept private methods). But even if it were public and annotated:

```java
@Service
public class BadExample {

    public void outerMethod() {
        innerMethod();  // BUG: this calls `this.innerMethod()`, not `proxy.innerMethod()`
    }

    @Transactional
    public void innerMethod() {
        // The @Transactional on innerMethod is COMPLETELY IGNORED
        // when called from outerMethod in the same bean.
    }
}
```

### Why This Happens

The proxy wraps the **bean reference that other beans hold**. When code inside the bean calls `this.innerMethod()`, it bypasses the proxy entirely. The proxy never knows the call happened.

```
ExternalCaller → proxy.outerMethod() → [PROXY INTERCEPTS HERE]
                                     → this.outerMethod()   [your code]
                                     → this.innerMethod()   [direct call, NO PROXY]
```

### Solutions

**Solution 1: Inject self-reference (AopContext)**

```java
@Service
public class MyService {

    public void outerMethod() {
        // Get the proxy reference to yourself
        MyService self = (MyService) AopContext.currentProxy();
        self.innerMethod();  // Now goes through the proxy
    }

    @Transactional
    public void innerMethod() { ... }
}
```

Requires `@EnableAspectJAutoProxy(exposeProxy = true)` in config. Works but feels hacky.

**Solution 2: Extract to a separate bean (preferred)**

```java
@Service
public class HearingProcessor {
    @Transactional
    public void processHearing(Hearing hearing) { ... }
}

@Component
public class NotificationScheduler {
    private final HearingProcessor hearingProcessor;  // injected — goes through proxy

    public void sendHearingReminders() {
        for (Hearing hearing : hearings) {
            hearingProcessor.processHearing(hearing);  // proxy intercepts this
        }
    }
}
```

This is cleaner. It also forces better separation of concerns.

**Solution 3: ApplicationContext lookup**

```java
@Service
public class MyService implements ApplicationContextAware {
    private ApplicationContext ctx;

    public void outerMethod() {
        ctx.getBean(MyService.class).innerMethod();  // get the proxied version
    }
}
```

Avoid this. It ties your code to the Spring container in an ugly way.

### VaadVivaad Reality Check

In `NotificationScheduler`, `processHearing` is **private**. Private methods can never be `@Transactional`. The `@Transactional(readOnly = true)` on `sendHearingReminders` covers the read of hearings. The `processHearing` call is inside that transaction, which is fine. If `processHearing` needed its own transaction boundary, it would need to move to a separate bean.

---

## 7. Rollback Rules

### The Critical Asymmetry: Checked vs. Unchecked Exceptions

In Java, exceptions split into two families:

```
Throwable
├── Error (JVM-level — OutOfMemoryError, StackOverflowError)
└── Exception
    ├── RuntimeException (unchecked — no `throws` declaration required)
    │   ├── IllegalArgumentException
    │   ├── IllegalStateException
    │   ├── NullPointerException
    │   └── ... (all your custom service exceptions should extend these)
    └── Checked Exceptions (require `throws` or try/catch)
        ├── IOException
        ├── SQLException
        └── ...
```

**Spring's default rollback rule:**

| Exception type | Default behavior |
|---|---|
| `RuntimeException` (unchecked) | **ROLLBACK** |
| `Error` | **ROLLBACK** |
| Checked `Exception` | **COMMIT** (no rollback) |

This surprises everyone coming from Node, where all exceptions are just `throw new Error(...)`.

### Why the Asymmetry?

It's a design philosophy from the Java architects:
- Checked exceptions often represent **recoverable conditions** (file not found, network timeout) — you might want to commit what you've done so far and handle the error
- Runtime exceptions often represent **programming errors or unrecoverable state** — roll everything back

Spring adopted this convention. You can override it.

### VaadVivaad Uses RuntimeException Correctly

```java
// AuthService.java — register()
if (userRepository.existsByEmail(request.email())) {
    throw new IllegalArgumentException("Email already registered: " + request.email());
    // ^ RuntimeException → @Transactional will ROLLBACK
}

// SubscriptionService.java — subscribe()
if (subscriptionRepository.existsByUserIdAndCourtCaseId(...)) {
    throw new IllegalArgumentException("Already subscribed to case: " + ...);
    // ^ RuntimeException → @Transactional will ROLLBACK
}
```

Both custom exceptions in the codebase extend `RuntimeException`:

```java
// These are RuntimeExceptions — Spring rolls back on them
throw new ResourceNotFoundException("Court case", "cnrNumber", cnrNumber);
throw new CnrValidationException("Case with CNR " + ... + " already exists");
```

This is the correct pattern. Your custom business exceptions should always extend `RuntimeException`.

### Overriding Rollback Behavior

```java
// Roll back on ANY exception (including checked)
@Transactional(rollbackFor = Exception.class)
public void criticalOperation() throws IOException {
    // If IOException is thrown, transaction rolls back
}

// Don't roll back on a specific exception
@Transactional(noRollbackFor = OptimisticLockingFailureException.class)
public void updateWithRetry() {
    // Handles optimistic locking itself — doesn't need rollback
}
```

### When Would You Use `rollbackFor = Exception.class`?

When you have a method that throws a checked exception and you want full rollback semantics:

```java
@Transactional(rollbackFor = Exception.class)
public void importCsv(MultipartFile file) throws IOException {
    // If file parsing throws IOException mid-way through,
    // we want to roll back any partial inserts
    for (String line : readLines(file)) {
        repository.save(parseLine(line));
    }
}
```

---

## 8. The RabbitMQ + @Transactional Gap

This is the most important architectural insight in VaadVivaad's current codebase. Understanding this gap — and the solution — is what separates junior from senior.

### The Problem

Look at `SubscriptionService.subscribe()`:

```java
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    // ...
    Subscription saved = subscriptionRepository.save(subscription);  // DB write

    rabbitTemplate.convertAndSend(                                    // MQ publish
            RabbitMQConfig.EXCHANGE,
            RabbitMQConfig.ROUTING_KEY,
            event
    );

    return toResponse(saved, courtCase);
    // ^ @Transactional proxy commits the DB transaction here
}
```

The DB save and the MQ publish are NOT in the same transaction. RabbitMQ has no concept of a JDBC transaction. This creates two failure scenarios:

**Scenario A: DB commit succeeds, then the line after publish fails**
```
subscriptionRepository.save()  → OK
rabbitTemplate.convertAndSend() → OK (message is in RabbitMQ)
// Imagine something after this throws an exception
// @Transactional sees a RuntimeException → ROLLBACK the DB
// But RabbitMQ already received the message — it cannot be recalled
// Result: no subscription in DB, but notification email sent. Phantom message.
```

**Scenario B: DB commits, but message was never published (network glitch)**
```
subscriptionRepository.save()   → OK
rabbitTemplate.convertAndSend() → FAILS (network issue, RabbitMQ down)
// Exception bubbles up
// @Transactional rolls back the DB save
// Result: no subscription in DB, no message sent. Fine, user retries.
// BUT: if the DB commit happened before the MQ publish fails... orphaned record.
```

Look at `ScraperService` — it's even more explicit about this gap:

```java
// ScraperService.java — the comment in the code acknowledges this directly:
/*
 * WHY inside @Transactional?
 * If RabbitMQ publish fails, the transaction rolls back...
 * If the transaction rolls back after publish — the consumer
 * will try to fetch a non-existent hearing ID...
 * That is acceptable for MVP. Production would use transactional
 * outbox pattern to guarantee exactly-once delivery.
 */
rabbitTemplate.convertAndSend(
        RabbitMQConfig.SUMMARY_EXCHANGE,
        RabbitMQConfig.SUMMARY_ROUTING_KEY,
        event
);
```

### The Solution: Transactional Outbox Pattern

Instead of publishing directly to RabbitMQ, write the event to an `outbox` table **in the same database transaction** as your business data. A separate process (a Debezium CDC connector, or a scheduled poller) reads from the outbox table and publishes to RabbitMQ.

```
Your @Transactional method:
  ├── INSERT INTO subscriptions ...    (business data)
  └── INSERT INTO outbox_events ...   (event record)
  → Both commit atomically

Outbox processor (separate thread/service):
  ├── SELECT * FROM outbox_events WHERE published = false
  ├── rabbitTemplate.convertAndSend(...)
  └── UPDATE outbox_events SET published = true
```

Now the DB and the MQ are eventually consistent. No phantom messages. No lost messages.

### The Spring Fix: @TransactionalEventListener

A simpler Spring-native approach is covered in section 11. The point here is: **publishing to RabbitMQ inside a @Transactional method is a known gap in VaadVivaad**. Be able to explain it, explain why it's acceptable for MVP, and explain what the production fix would be.

---

## 9. Service Layer Patterns in VaadVivaad

### Pattern 1: ScraperService — Upsert + Freshness Check + Event Publishing

```java
// ScraperService.java
@Transactional
@CacheEvict(cacheNames = "cases", allEntries = true)
public CourtCase scrapeOrRefresh(String cnrNumber) {

    // 1. Check DB first (not Redis — DB is source of truth for case data)
    Optional<CourtCase> existing = courtCaseRepository.findByCnrNumber(cnrNumber);

    // 2. Freshness guard — avoid hammering eCourts
    if (existing.isPresent() && isFresh(existing.get())) {
        return existing.get();  // return early, no scrape
    }

    // 3. External call (outside transaction — HTTP to eCourts)
    String html = eCourtWebClient.fetchCaseHtml(cnrNumber);

    // 4. Parse
    ParsedCaseData parsedData = htmlParser.parse(html);

    // 5. Upsert (update if exists, insert if not)
    CourtCase savedCase = upsertCase(parsedData, existing);

    return savedCase;
}
```

**The upsert pattern**:

```java
private CourtCase upsertCase(ParsedCaseData data, Optional<CourtCase> existing) {
    // If existing → reuse entity (JPA does UPDATE, preserves ID)
    // If not existing → new entity (JPA does INSERT)
    CourtCase courtCase = existing.orElse(new CourtCase());
    courtCase.setCnrNumber(data.cnrNumber());
    // ... set all fields

    CourtCase saved = courtCaseRepository.save(courtCase);

    // Full-replace strategy for hearings: delete all, reinsert fresh
    hearingRepository.deleteAllByCaseId(saved.getId());
    for (ParsedCaseData.ParsedHearing ph : data.hearings()) {
        Hearing hearing = new Hearing();
        // ... set fields
        Hearing savedHearing = hearingRepository.save(hearing);

        // Publish AFTER save so the consumer can find the hearing by ID
        rabbitTemplate.convertAndSend(SUMMARY_EXCHANGE, SUMMARY_ROUTING_KEY,
                new SummaryRequestEvent(savedHearing.getId(), data.cnrNumber()));
    }
    return saved;
}
```

Key insight: the `@CacheEvict` is on `scrapeOrRefresh`, not on `CaseLookupService`. This is clean responsibility: the scraper invalidates the cache because it changed the data. The lookup service just reads.

### Pattern 2: SubscriptionService — Business Rules Before Writes

```java
// SubscriptionService.java
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {

    // Step 1: Load authenticated user (identity from SecurityContext)
    String email = SecurityContextHolder.getContext().getAuthentication().getName();
    User user = userRepository.findByEmail(email)...;

    // Step 2: Load the domain object being acted upon
    CourtCase courtCase = courtCaseRepository.findById(request.caseId())...;

    // Step 3: Business rule (must come before write)
    if (subscriptionRepository.existsByUserIdAndCourtCaseId(...)) {
        throw new IllegalArgumentException("Already subscribed...");
    }

    // Step 4: Write
    Subscription saved = subscriptionRepository.save(subscription);

    // Step 5: Publish event (with the caveat from section 8)
    rabbitTemplate.convertAndSend(..., event);

    return toResponse(saved, courtCase);
}
```

The sequence matters. Always: load → validate → write → publish. Never publish before you know the write will succeed.

### Pattern 3: CaseLookupService — @Cacheable + @Transactional(readOnly)

```java
// CaseLookupService.java
@Transactional(readOnly = true)   // Hibernate skips dirty checking, JDBC optimizes
@Cacheable(value = "cases", key = "#cnrNumber")  // Redis cache hit → skips DB entirely
public CaseResponse lookupByCnr(String cnrNumber) {
    CourtCase courtCase = courtCaseRepository.findByCnrNumber(cnrNumber)...;
    List<Hearing> hearings = hearingRepository.findByCourtCaseIdOrderByHearingDateDesc(...);
    return mapToResponse(courtCase, hearings);
}
```

**Order of operations on a cache hit**: `@Cacheable` is checked first (by a Spring AOP proxy). If the value is in Redis, the method body never executes. `@Transactional` is never entered. The database is never touched. On a cache miss, both proxies are active: cache proxy calls through to the transaction proxy, which opens a transaction, runs your method, commits, and the cache proxy stores the result.

### Pattern 4: AuthService — Guard → Write → Generate Token

```java
// AuthService.java
@Transactional
public AuthResponse register(RegisterRequest request) {

    // Guard: check before writing (optimistic approach — no lock)
    if (userRepository.existsByEmail(request.email())) {
        throw new IllegalArgumentException("Email already registered");
    }

    // Write: save the user
    User saved = userRepository.save(user);

    // Generate token: purely in-memory, no DB involvement
    String token = jwtService.generateToken(buildUserDetails(saved));

    return new AuthResponse(token, saved.getEmail(), ...);
}
```

Note: `login` uses `@Transactional(readOnly = true)` because it only reads. The `authenticationManager.authenticate()` call triggers a DB read (loads user to verify password) but does not write. This is correct.

---

## 10. ApplicationEventPublisher

### What It Is

Spring has a built-in synchronous event bus. Instead of directly calling a method on another service, you "publish" an event. Other beans "listen" for it.

```java
// Publishing (typically in a service after a successful write)
@Autowired
private ApplicationEventPublisher eventPublisher;

@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    Subscription saved = subscriptionRepository.save(subscription);

    // Instead of calling notificationService.sendWelcomeEmail() directly:
    eventPublisher.publishEvent(new SubscriptionCreatedEvent(
            saved.getId(),
            user.getId(),
            user.getEmail(),
            // ...
    ));

    return toResponse(saved, courtCase);
}
```

### SubscriptionCreatedEvent in VaadVivaad

```java
// subscription/event/SubscriptionCreatedEvent.java
public record SubscriptionCreatedEvent(
        UUID subscriptionId,
        UUID userId,
        String userEmail,
        String userFullName,
        UUID caseId,
        String cnrNumber,
        String caseDisplay,
        LocalDateTime subscribedAt
) {}
```

VaadVivaad currently publishes `SubscriptionCreatedEvent` directly to RabbitMQ via `rabbitTemplate`, bypassing `ApplicationEventPublisher`. This works but is less testable and has the transaction gap from section 8.

### Synchronous by Default

By default, `publishEvent()` is synchronous:
- Same thread as the caller
- Same transaction as the caller
- If the listener throws, the caller's transaction rolls back

```java
// Listener in same transaction
@EventListener
public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
    // Runs in the same thread and transaction as the publisher
    // If this throws, the outer @Transactional rolls back everything
    emailService.sendWelcomeEmail(event.userEmail());
}
```

### Async Listeners

```java
@Async   // Runs in a separate thread pool thread
@EventListener
public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
    // Runs after the publisher's method returns (not after commit — just after return)
    // The outer transaction may or may not be committed yet
    emailService.sendWelcomeEmail(event.userEmail());
}
```

Requires `@EnableAsync` on a configuration class. The `@Async` listener runs after the publisher method returns, but the transaction may not yet be committed. This is where `@TransactionalEventListener` is the right tool.

---

## 11. @EventListener vs @TransactionalEventListener

### The Problem with @EventListener for External Systems

If you publish an event inside a `@Transactional` method, and your `@EventListener` sends an email or publishes to RabbitMQ:

```
@Transactional begins
  → subscriptionRepository.save(subscription)   [DB write, not committed yet]
  → publishEvent(SubscriptionCreatedEvent)
      → @EventListener fires IMMEDIATELY (same thread)
          → emailService.sendWelcomeEmail()    [EMAIL SENT]
  → exception thrown somewhere after
@Transactional ROLLBACK
// DB: subscription doesn't exist. Email: already sent. Gap.
```

### @TransactionalEventListener — The Fix

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
    // This method only runs if the @Transactional method COMMITTED.
    // If the outer transaction rolls back, this listener is NEVER called.
    emailService.sendWelcomeEmail(event.userEmail());
    rabbitTemplate.convertAndSend(..., event);  // Safe: DB is committed
}
```

The four phases:

| Phase | When it runs |
|---|---|
| `AFTER_COMMIT` | After the transaction commits successfully — **use this for external side effects** |
| `AFTER_ROLLBACK` | After the transaction rolls back — useful for cleanup/compensation |
| `AFTER_COMPLETION` | After commit OR rollback — useful for releasing resources |
| `BEFORE_COMMIT` | Just before commit — last chance to veto |

### VaadVivaad's Current Situation and the Fix

**Current (SubscriptionService):**
```java
// Publishes to RabbitMQ inside the @Transactional method — has the gap
rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
```

**Better approach using Spring events:**
```java
// In SubscriptionService — publish a Spring event, not directly to RabbitMQ
eventPublisher.publishEvent(new SubscriptionCreatedEvent(...));

// In a separate listener class:
@Component
public class SubscriptionEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
        // This ONLY runs after DB commit is confirmed.
        // Safe to publish to RabbitMQ now.
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
    }
}
```

This is not yet implemented in VaadVivaad, but knowing this pattern is a senior differentiator.

---

## 12. Thin Controllers Pattern

### The Rule

Controllers should do exactly three things:
1. Extract data from the HTTP request
2. Call a service method
3. Wrap the result in an HTTP response

No business logic. No database access. No if/else deciding what the domain rules are.

### VaadVivaad Controllers Are Thin

```java
// CaseLookupController.java — notice how thin this is
@RestController
@RequestMapping("/api/cases")
public class CaseLookupController {

    private final CaseLookupService caseLookupService;

    @PostMapping
    public ResponseEntity<ApiResponse<CaseResponse>> createCase(
            @Valid @RequestBody CreateCaseRequest request) {
        CaseResponse created = caseLookupService.createCase(request);  // ONE LINE
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(created, "Case created successfully"));
    }

    @PostMapping("/lookup")
    public ResponseEntity<ApiResponse<CaseResponse>> lookupByCnr(
            @Valid @RequestBody CnrLookupRequest request) {
        CaseResponse caseResponse = caseLookupService.lookupByCnr(request.cnrNumber());
        return ResponseEntity.ok(ApiResponse.success(caseResponse, "Case found"));
    }
}
```

```java
// SubscriptionController.java — equally thin
@PostMapping
public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(
        @Valid @RequestBody SubscriptionRequest request) {
    SubscriptionResponse response = subscriptionService.subscribe(request);  // ONE LINE
    return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(response));
}

@DeleteMapping("/{subscriptionId}")
public ResponseEntity<ApiResponse<Void>> unsubscribe(
        @PathVariable UUID subscriptionId) {
    subscriptionService.unsubscribe(subscriptionId);  // ONE LINE
    return ResponseEntity.ok(ApiResponse.success(null));
}
```

### What a Fat Controller Looks Like (Anti-Pattern)

```java
// DON'T DO THIS
@PostMapping("/subscribe")
public ResponseEntity<?> subscribe(@RequestBody SubscriptionRequest request) {
    // Business logic in controller — wrong layer
    String email = SecurityContextHolder.getContext().getAuthentication().getName();
    User user = userRepository.findByEmail(email).orElseThrow();

    // DB access in controller — wrong layer
    CourtCase courtCase = courtCaseRepository.findById(request.caseId()).orElseThrow();

    // Business rule in controller — wrong layer
    if (subscriptionRepository.existsByUserIdAndCourtCaseId(user.getId(), courtCase.getId())) {
        return ResponseEntity.status(409).body("Already subscribed");
    }

    // @Transactional annotation here is useless — controllers are not proxied this way
    Subscription sub = subscriptionRepository.save(...);
    return ResponseEntity.status(201).body(sub);
}
```

The problems:
- You cannot test the "already subscribed" rule without an HTTP test
- You cannot call this logic from a batch job
- `@Transactional` on a controller method works technically but is a design smell
- The controller now knows about `UserRepository`, `CourtCaseRepository`, `SubscriptionRepository` — it has too many dependencies

---

## 13. DTOs — Why Not Return Entities

### The Three Problems with Returning JPA Entities Directly

**Problem 1: LazyInitializationException**

JPA entities have lazy-loaded relationships. If you return a `CourtCase` entity and the HTTP response serializer (Jackson) tries to serialize `courtCase.getHearings()` after the transaction has closed, you get:

```
org.hibernate.LazyInitializationException: 
  could not initialize proxy - no Session
```

The Hibernate session closes when the `@Transactional` method returns. Jackson runs after that. Game over.

**Problem 2: Exposing Internal Fields**

```java
// If you return CourtCase entity directly, Jackson serializes ALL fields:
{
  "id": "...",
  "cnrNumber": "...",
  "hearings": [...],
  "subscriptions": [...]  // ← you're exposing user subscription data
                          //   on a case lookup endpoint. Security issue.
}
```

**Problem 3: Tight Coupling**

If you refactor your entity (rename a field, add a column, change a relationship), every API client breaks immediately. A DTO is a stable contract between your API and the world. Your entity can change freely.

### VaadVivaad: CourtCase Entity vs CaseResponse DTO

**Entity** (internal — JPA maps this to the DB):
```java
// CourtCase.java — has ALL persistence concerns
@Entity
@Table(name = "court_cases")
public class CourtCase extends Auditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Hearing> hearings = new ArrayList<>();   // lazy by default

    @OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Subscription> subscriptions = new ArrayList<>();  // user data — internal

    // ... plus createdAt, updatedAt from Auditable
}
```

**DTO** (external — what the API returns):
```java
// CaseResponse.java — a Java record, immutable, no JPA annotations
public record CaseResponse(
    UUID id,
    String cnrNumber,
    String caseType,
    String filingNumber,
    LocalDate filingDate,
    String registrationNumber,
    LocalDate registrationDate,
    String status,         // Note: String, not CaseStatus enum — API-friendly
    String petitioner,
    String respondent,
    String courtName,
    String judgeName,
    LocalDateTime lastScrapedAt,
    List<HearingResponse> hearings  // already-loaded, no lazy loading risk
    // Note: no 'subscriptions' field — internal data, not exposed
) {}
```

Key differences:
- `status` is `String` in the DTO (not the `CaseStatus` enum) — API clients don't know about your Java enums
- `subscriptions` is NOT in the DTO — internal data
- `hearings` in the DTO is `List<HearingResponse>` (another DTO) — already loaded, no lazy loading risk
- No `@Entity`, `@Column`, `@OneToMany` etc — clean, dependency-free

### Mapping Happens in the Service

```java
// CaseLookupService.java
private CaseResponse mapToResponse(CourtCase courtCase, List<Hearing> hearings) {
    List<HearingResponse> hearingResponses = hearings.stream()
        .map(this::mapHearingToResponse)
        .toList();

    return new CaseResponse(
        courtCase.getId(),
        courtCase.getCnrNumber(),
        // ... all fields
        courtCase.getStatus().name(),  // enum → String
        // ...
        hearingResponses
    );
}
```

The mapping lives in the service, not the controller. The controller receives `CaseResponse` and knows nothing about `CourtCase`.

---

## 14. @Valid and the Validation Flow

### How `@Valid` Works

```java
// CaseLookupController.java
@PostMapping
public ResponseEntity<ApiResponse<CaseResponse>> createCase(
        @Valid @RequestBody CreateCaseRequest request) {
    // If @Valid fails, this method body NEVER executes.
    // Spring throws MethodArgumentNotValidException before reaching here.
    CaseResponse created = caseLookupService.createCase(request);
    ...
}
```

When `@Valid` is present, Spring runs JSR-380 (Bean Validation) on the deserialized object **before** calling your method. Validation annotations live on the DTO:

```java
// CreateCaseRequest.java (typical pattern)
public record CreateCaseRequest(
    @NotBlank(message = "CNR number is required")
    @Pattern(regexp = "[A-Z]{2}[0-9]{2}[A-Z0-9]+", message = "Invalid CNR format")
    String cnrNumber,

    @NotBlank
    String caseType,

    @NotNull
    LocalDate filingDate,

    List<CreateHearingRequest> hearings  // no @NotNull — optional
) {}
```

### The Full Flow

```
HTTP POST /api/cases
  → DispatcherServlet
  → HandlerMapping identifies CaseLookupController.createCase
  → HttpMessageConverter deserializes JSON → CreateCaseRequest
  → @Valid triggers Bean Validation (JSR-380)
      → If INVALID: Spring throws MethodArgumentNotValidException
          → GlobalExceptionHandler catches it
          → Returns 400 Bad Request with field-level errors
      → If VALID: Controller method is invoked
          → caseLookupService.createCase(request) is called
```

### GlobalExceptionHandler Catches Validation Failures

```java
// GlobalExceptionHandler.java (typical pattern in VaadVivaad)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                FieldError::getDefaultMessage
            ));

        return ResponseEntity
            .badRequest()
            .body(ApiResponse.error("Validation failed: " + errors));
    }
}
```

### ApiResponse as the Universal Envelope

```java
// ApiResponse.java — a record used for ALL responses
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data, String message) { ... }
    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> error(String message) { ... }
}
```

This envelope means every API response has the same shape. Clients always check `success`, always find data in `data`, always find errors in `message`. Consistent. Predictable.

---

## 15. Node → Java Comparison Table

| Concern | Express (Node) | Spring Boot (Java) |
|---|---|---|
| Route definition | `app.post('/cases', handler)` | `@PostMapping` on controller method |
| Request body | `req.body` (already parsed by express.json()) | `@RequestBody CreateCaseRequest request` |
| Path variable | `req.params.id` | `@PathVariable UUID id` |
| Query params | `req.query.page` | `@PageableDefault Pageable pageable` |
| Validation | Manual: `if (!body.cnr) return res.status(400)...` | `@Valid` + JSR-380 annotations on DTO |
| Business logic | Often in route handler | In `@Service` class |
| DB access | `db.query(sql, params)` or ORM call | `@Repository` / Spring Data JPA |
| Transaction | `BEGIN`/`COMMIT`/`ROLLBACK` explicitly, or library | `@Transactional` (proxy handles it) |
| Error handling | `try/catch` in route, or `next(err)` middleware | `@RestControllerAdvice` GlobalExceptionHandler |
| Response shape | `res.json({ data: ..., success: true })` manually | `ResponseEntity<ApiResponse<T>>` |
| Async | `async/await`, Promises | Blocking by default; `@Async` for explicit async |
| DI | Manual: `const service = new Service(repo)` | `@Autowired` / constructor injection |
| Environment config | `process.env.DB_URL` | `@Value("${spring.datasource.url}")` |
| Caching | Redis client, manual `get`/`set` | `@Cacheable`, `@CacheEvict` annotations |

### The Biggest Conceptual Shift

In Node, you typically wire everything yourself:
```javascript
const userRepo = new UserRepository(dbPool);
const authService = new AuthService(userRepo, bcrypt, jwt);
app.post('/register', (req, res) => authService.register(req.body));
```

In Spring, the IoC container wires everything:
```java
// Spring sees @Service, @Repository, and wires them automatically
// You just declare what you need (constructor injection)
@Service
public class AuthService {
    private final UserRepository userRepository;  // Spring injects this

    public AuthService(UserRepository userRepository, ...) { ... }
}
```

The proxy-based AOP (@Transactional, @Cacheable, @Valid) is then layered on top of this DI graph. Nothing like this exists natively in Express. The closest Express equivalent is middleware — but middleware is per-route, not per-method on a service.

---

## 16. Senior Interview Q&A

### Q1: What does @Transactional actually do at runtime?

**A:** Spring creates a CGLIB proxy around the `@Service` bean at startup. When a caller invokes an annotated method, the proxy intercepts the call, acquires a JDBC connection, sets `autoCommit=false`, runs your method, and then either commits (on normal return) or rolls back (on RuntimeException). Your actual class is unchanged — the proxy is a generated subclass. This means `@Transactional` only works on external method calls — calls from within the same class bypass the proxy entirely.

---

### Q2: When does @Transactional NOT roll back?

**A:** By default, Spring only rolls back on `RuntimeException` and `Error`. Checked exceptions (those that extend `Exception` but not `RuntimeException`) cause the transaction to **commit**, not roll back. This surprises developers coming from Node where all exceptions are runtime. You fix this with `@Transactional(rollbackFor = Exception.class)` to make all exceptions trigger rollback.

---

### Q3: Explain the self-invocation trap.

**A:** If method A in a `@Service` class calls method B in the same class, and B is `@Transactional`, the annotation on B is ignored. The proxy only wraps the bean reference that external callers hold. Internal calls use `this.methodB()` which bypasses the proxy. The fix is to extract B into a separate Spring bean so that the call goes through the proxy. In VaadVivaad, `processHearing` in `NotificationScheduler` is private, so it cannot be `@Transactional` — if it needed a transaction, it would need to be extracted to a separate `@Service`.

---

### Q4: What is REQUIRES_NEW propagation and when would you use it?

**A:** `REQUIRES_NEW` always creates a new, independent transaction, suspending any existing outer transaction. Use it for operations that must commit regardless of what happens to the outer transaction — the canonical example is audit logging. In VaadVivaad, if we wanted to log every subscription attempt (even failed ones), we'd put that in a service method with `REQUIRES_NEW` so the audit record commits even if the subscription itself rolls back.

---

### Q5: What is the RabbitMQ + @Transactional gap in VaadVivaad?

**A:** `SubscriptionService.subscribe()` saves to the DB and publishes to RabbitMQ in the same method. The DB write is inside a JDBC transaction; the RabbitMQ publish is not — RabbitMQ has no concept of JDBC transactions. If the DB commits but something fails after the publish, the message is already in RabbitMQ but the listener may encounter inconsistent data. If the DB rolls back after the publish, the message was published for an operation that didn't persist. The production fix is either the Transactional Outbox pattern or `@TransactionalEventListener(phase = AFTER_COMMIT)` which runs only after the DB transaction commits.

---

### Q6: What are the three benefits of @Transactional(readOnly = true)?

**A:** First, Hibernate skips dirty checking at the end of the transaction — it doesn't compare each loaded entity to its original snapshot, which is an O(n) operation. Second, the JDBC driver gets a hint that the connection is read-only, enabling driver-level optimizations. Third, in architectures with read replicas, the connection pool can route read-only transactions to a replica, scaling read traffic without any code changes. In VaadVivaad, all three read methods in `CaseLookupService` use `readOnly = true`.

---

### Q7: Why doesn't VaadVivaad return `CourtCase` entities directly from the API?

**A:** Three reasons. First, `CourtCase` has lazy-loaded `@OneToMany` relationships (`hearings`, `subscriptions`). When Jackson tries to serialize these after the `@Transactional` method returns, the Hibernate session is closed and you get `LazyInitializationException`. Second, returning the entity exposes internal fields — in this case, `subscriptions`, which would leak user data on a case lookup endpoint. Third, tight coupling: if the entity schema changes, the API contract changes. The `CaseResponse` record is a stable contract between the API and clients, independent of how the entity is stored.

---

### Q8: What is the difference between @EventListener and @TransactionalEventListener?

**A:** `@EventListener` fires synchronously when `publishEvent()` is called — which is inside the active transaction if one exists. If the listener sends an email and then the outer transaction rolls back, the email was already sent. `@TransactionalEventListener(phase = AFTER_COMMIT)` only fires after the transaction has committed successfully. If the transaction rolls back, the listener never fires. For external side effects (sending emails, publishing to RabbitMQ), `AFTER_COMMIT` is always safer.

---

### Q9: Explain the layering in VaadVivaad with a concrete request flow.

**A:** Take `POST /api/subscriptions`. The request hits `SubscriptionController.subscribe()`. The controller's only job is to extract the `SubscriptionRequest` from the request body (validated by `@Valid`) and call `subscriptionService.subscribe(request)`. The controller returns the `SubscriptionResponse` wrapped in `ApiResponse<SubscriptionResponse>`. Inside `SubscriptionService.subscribe()` — which is `@Transactional` — the service fetches the authenticated user from `SecurityContextHolder`, fetches the `CourtCase` by ID, enforces the "no duplicate subscription" business rule, saves the `Subscription`, publishes a `SubscriptionCreatedEvent` to RabbitMQ, and returns a `SubscriptionResponse` DTO. The service never touches HTTP. The controller never touches the database.

---

### Q10: What is the upsert pattern in ScraperService and why is it needed?

**A:** The database has a unique constraint on `cnr_number`. If `scrapeOrRefresh` always created a new `CourtCase` entity, re-scraping an existing case would violate the unique constraint. Instead, `upsertCase` does `existing.orElse(new CourtCase())` — if the case already exists, it reuses that entity's ID, and JPA performs an UPDATE. If it's a new case, JPA performs an INSERT. For hearings, the scraper uses a full-replace strategy: delete all existing hearings for the case, then insert fresh ones. This is simpler than trying to merge hearing records by date, which is fragile when hearings can be rescheduled or cancelled.

---

## 17. Senior Differentiators

These are the things junior and mid-level developers do not know. Saying these in an interview separates you.

### On @Transactional

> "The annotation works through a CGLIB proxy. This means it only works on public methods called from outside the bean. I always watch for self-invocation bugs, especially in services that have helper methods."

> "I always use `@Transactional(readOnly = true)` on query methods — it's not ceremonial, it skips Hibernate dirty checking and can route to read replicas."

> "By default, Spring only rolls back on RuntimeException. If you're calling legacy code that throws checked exceptions and you need rollback, you must add `rollbackFor = Exception.class` or you will silently commit bad state."

### On the RabbitMQ Gap

> "Publishing to a message broker inside a `@Transactional` method has a known consistency gap. The DB and the broker are not in the same transaction. In VaadVivaad, we accept this for MVP and document it. The production fix is either the Transactional Outbox pattern or using `@TransactionalEventListener(phase = AFTER_COMMIT)` to only publish after the DB confirms the commit."

### On DTOs

> "We never return JPA entities from our API layer. The entity has lazy relationships that throw `LazyInitializationException` when serialized after the transaction closes. The DTO is a stable API contract independent of the persistence model."

### On Propagation

> "The default propagation is REQUIRED — join an existing transaction or create one. The most important non-default is REQUIRES_NEW, which I'd use for audit logging that must persist even if the outer transaction rolls back."

### On Service Layer Design

> "The service layer is where `@Transactional` belongs, not the controller. This means the same business logic can be called from REST controllers, scheduled jobs, message listeners, and test cases — all without duplicating the transaction boundary."

### On Isolation

> "PostgreSQL defaults to READ_COMMITTED, which prevents dirty reads but allows non-repeatable reads. For financial operations where you read-then-write based on a value, you'd step up to REPEATABLE_READ. SERIALIZABLE is the only level that prevents phantom reads but at significant concurrency cost."

### On Testing

> "Because business logic lives in the service layer, I can unit test `SubscriptionService.subscribe()` by mocking the repositories — no HTTP server, no Spring context required. The controller is so thin it barely needs unit testing; integration tests cover it."

---

*Source files reviewed: `CaseLookupService.java`, `SubscriptionService.java`, `ScraperService.java`, `AuthService.java`, `NotificationScheduler.java`, `CaseLookupController.java`, `SubscriptionController.java`, `ApiResponse.java`, `CaseResponse.java`, `CourtCase.java`*
