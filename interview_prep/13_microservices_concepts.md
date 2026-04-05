# 13 — Microservices Concepts

> **VaadVivaad context:** Spring Boot 3.4.4 / Java 21. The system is a **modular monolith** — one
> deployable JAR, four internal modules (scraper, notification, ai, lookup), sharing one DB and one
> RabbitMQ broker. Understanding this honestly — and knowing when a monolith is the right call — is
> the senior answer.

---

## Table of Contents

1. [Monolith vs Microservices — Honest Trade-offs](#1-monolith-vs-microservices)
2. [VaadVivaad as a Microservices Candidate](#2-vaadvivaad-as-a-microservices-candidate)
3. [Inter-Service Communication](#3-inter-service-communication)
4. [Service Discovery](#4-service-discovery)
5. [API Gateway](#5-api-gateway)
6. [Circuit Breaker Pattern](#6-circuit-breaker-pattern)
7. [Resilience4j in Spring Boot](#7-resilience4j-in-spring-boot)
8. [Distributed Transactions — The Hard Problem](#8-distributed-transactions)
9. [Saga Pattern](#9-saga-pattern)
10. [Event Sourcing](#10-event-sourcing)
11. [CQRS](#11-cqrs)
12. [Distributed Caching](#12-distributed-caching)
13. [Health Checks and Readiness](#13-health-checks-and-readiness)
14. [Distributed Tracing](#14-distributed-tracing)
15. [12-Factor App Principles](#15-12-factor-app-principles)
16. [Containerization and Kubernetes Concepts](#16-containerization-and-kubernetes)
17. [Service Mesh](#17-service-mesh)
18. [When NOT to Use Microservices](#18-when-not-to-use-microservices)
19. [Node → Java Comparison](#19-node--java-comparison)
20. [Senior Interview Q&A](#20-senior-interview-qa)
21. [Senior Differentiators](#21-senior-differentiators)

---

## 1. Monolith vs Microservices

### The mental model first

A monolith is not a bad word. A microservice is not automatically better. They are different
*deployment and organizational strategies* with different trade-off profiles. The senior developer
knows which trade-offs they are buying into.

### What a Monolith gives you

```
All modules compile into one artifact → one JAR → one process
```

- **Simpler deployment.** One thing to build, one thing to deploy, one thing to monitor.
- **No network overhead.** A call from `ScraperService` to `NotificationScheduler` is a Java method
  call — nanoseconds, not HTTP round trips.
- **ACID transactions across the entire system.** In VaadVivaad, `ScraperService.upsertCase()` is
  `@Transactional`. It saves the `CourtCase`, saves all `Hearing` rows, and publishes RabbitMQ
  messages — all in one DB transaction. If any hearing save fails, everything rolls back. You simply
  cannot do this across microservices without additional coordination.
- **Easier local development.** One `docker-compose up`, one JVM, done.
- **Simpler debugging.** One thread, one stack trace, one log file.

### What Microservices give you

```
Each service is its own artifact → independent lifecycle
```

- **Independent scaling.** If scraping is the bottleneck, scale only the scraper. Don't pay for
  extra auth service instances.
- **Independent deployment.** Ship a change to the AI summary logic without touching or redeploying
  auth.
- **Technology diversity.** The scraper could be Python (Playwright), the AI layer could be Node.js,
  the REST API could be Spring Boot. Each team picks the right tool.
- **Fault isolation.** If the AI summary service crashes, case lookups still work.

### The cost you pay

| Cost | Why it hurts |
|------|-------------|
| Network calls | Every service boundary is now HTTP/gRPC. Latency + failure modes |
| No ACID across services | Must use saga, 2PC, or eventual consistency |
| Distributed tracing | A single user request spans 5 services. Debugging is hard |
| Operational complexity | Each service needs: deploy pipeline, health check, logging, scaling |
| Versioning | Service A v2 must stay compatible with Service B v1 |
| More infra | Each service may need its own DB, queue, cache |

### VaadVivaad is a modular monolith

```
vaadvivaad-backend.jar
├── com.vaadvivaad.scraper        ← Scraper module
├── com.vaadvivaad.ai             ← AI module
├── com.vaadvivaad.notification   ← Notification module
├── com.vaadvivaad.lookup         ← Case lookup module
└── com.vaadvivaad.config         ← Shared config
```

These are Java packages, not deployed services. But the internal design is clean enough that
extracting them into real services is *possible* — that is the definition of a good modular
monolith.

---

## 2. VaadVivaad as a Microservices Candidate

If you were asked "how would you split VaadVivaad into microservices?", here is the honest answer,
including what would be hard.

### Proposed service decomposition

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Gateway                              │
│              (routing, auth check, rate limiting)               │
└────┬─────────┬──────────┬──────────────┬──────────────┬─────────┘
     │         │          │              │              │
     ▼         ▼          ▼              ▼              ▼
┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐
│  Auth   │ │  Case    │ │ Scraper  │ │Notif.    │ │  AI Summary  │
│ Service │ │ Service  │ │ Service  │ │Service   │ │  Service     │
│         │ │          │ │          │ │          │ │              │
│ - Users │ │ - CRUD   │ │ - eCourt │ │ - WA/SMS │ │ - Claude API │
│ - JWT   │ │ - Lookup │ │   fetch  │ │ - Email  │ │ - Summaries  │
│ - Auth  │ │ - Search │ │ - Parse  │ │ - Sched. │ │              │
└─────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────────┘
     │              │          │              │              │
     └──────────────┴──────────┴──────────────┴──────────────┘
                         RabbitMQ / Kafka
```

### Service responsibilities

**Auth Service**
- Everything in `com.vaadvivaad.auth`: registration, login, JWT issue
- Has its own `users` table
- All other services trust the JWT — they don't call Auth Service for every request

**Case Service**
- `com.vaadvivaad.lookup`: CourtCase and Hearing CRUD, subscriptions
- Has its own DB tables: `court_cases`, `hearings`, `subscriptions`
- Exposes REST: `GET /cases/{cnr}`, `POST /subscriptions`

**Scraper Service**
- `com.vaadvivaad.scraper`: `ECourtWebClient`, `ECourtHtmlParser`, `ScraperService`
- Triggered via event (RabbitMQ) or internal scheduler
- Publishes scraped data back as events for Case Service to persist

**Notification Service**
- `com.vaadvivaad.notification`: WhatsApp, SMS, email dispatch
- Consumes `hearing.reminder` events from RabbitMQ
- Owns its own `notification_logs` table

**AI Summary Service**
- `com.vaadvivaad.ai`: `ClaudeClient`, `SummaryService`
- Consumes `summary.request` events
- Calls Claude API, stores summaries back

### What would be hard to split

**1. The shared database**

Right now, `ScraperService` writes to `court_cases` and `hearings` tables. `NotificationScheduler`
reads from both. The AI service writes to `hearing_summaries`. They all share one PostgreSQL
instance and one JPA context.

In microservices: each service owns its data. The Scraper Service cannot directly read from the
Case Service's DB. It must ask via API or event. This means:
- Every cross-service data need becomes an async event or sync API call
- You lose the ability to `JOIN` across service boundaries at the DB level

**2. ACID transactions**

In `ScraperService.upsertCase()`:

```java
@Transactional
@CacheEvict(cacheNames = "cases", allEntries = true)
public CourtCase scrapeOrRefresh(String cnrNumber) {
    // Save CourtCase
    CourtCase saved = courtCaseRepository.save(courtCase);
    
    // Delete old hearings, save new ones
    hearingRepository.deleteAllByCaseId(saved.getId());
    
    // For each hearing: save hearing + publish summary request to RabbitMQ
    rabbitTemplate.convertAndSend(SUMMARY_EXCHANGE, SUMMARY_ROUTING_KEY, event);
    
    return saved;
}
```

This is one DB transaction. In microservices: Scraper Service publishes a `CaseScraped` event →
Case Service persists the case → Case Service publishes `HearingCreated` event → AI Service
generates summary. If any step fails, you need a saga (see section 9) to compensate. No single
`@Transactional` annotation saves you anymore.

**3. The `@Scheduled` jobs**

`NotificationScheduler` runs `@Scheduled(cron = "0 0 8 * * *")`. In a microservices world, if you
have 3 instances of Notification Service, all three would fire at 8 AM. You need distributed
scheduling (ShedLock, Quartz cluster mode, or a dedicated scheduler service).

**The honest answer:** For VaadVivaad at current scale (one developer, MVP), the modular monolith
is the correct choice. Microservices would add 80% more operational work for 0% benefit — there
are no independent scaling needs yet. The right time to split is when: (a) you have different
scaling needs per component, or (b) you have different teams owning different parts, or (c) a
specific module has become a deployment bottleneck.

---

## 3. Inter-Service Communication

### The fundamental choice: synchronous vs asynchronous

This is not a technical preference. It is a business question: **does the caller need the result
right now?**

### Synchronous: REST / gRPC

```
Client ──── HTTP POST /summarize ────► Service B
       ◄─── 200 OK { summary: "..." } ──

Caller BLOCKS until response arrives.
```

**When to use:** Query patterns. "Give me the case details for CNR X." You need the data to
render the page. The user is waiting.

**REST (HTTP/JSON)**
- Simple, universal, human-readable
- WebClient in Spring Boot (reactive, non-blocking I/O)
- Every browser, every language can call it

```java
// VaadVivaad's ClaudeClient already does synchronous HTTP
ClaudeResponse response = claudeWebClient.post()
    .uri(MESSAGES_PATH)
    .bodyValue(request)
    .retrieve()
    .bodyToMono(ClaudeResponse.class)
    .timeout(Duration.ofSeconds(timeoutSeconds))
    .block();  // ← this makes it synchronous (blocks the thread)
```

**gRPC (Protocol Buffers over HTTP/2)**
- Binary protocol, faster than JSON
- Strongly typed via `.proto` schema files
- Bidirectional streaming
- Better for internal service-to-service calls with high throughput
- Harder to debug (binary, not human-readable)

**The problem with synchronous calls:** tight coupling on *availability*. If Service B is down,
Service A fails too. If Service B is slow, Service A blocks. This is why circuit breakers exist
(see section 6).

### Asynchronous: RabbitMQ / Kafka

```
Scraper ──── publish(SummaryRequestEvent) ────► RabbitMQ ──► AI Service
        ◄─── returns immediately (no wait)                  (processes when ready)
```

**VaadVivaad already uses this pattern.** After a scrape, `ScraperService` publishes a
`SummaryRequestEvent` to `summary.exchange` with routing key `summary.request`. The AI consumer
processes it independently. The scraper does not wait for the summary.

**When to use:** Command patterns. "Process this hearing and generate a summary." The scraper
doesn't need the summary to return the scraped case. Fire and move on.

**RabbitMQ:** push-based, message routing (exchanges/queues), per-message acknowledgement, good
for task queues. VaadVivaad uses Direct Exchange — one routing key → one queue.

**Kafka:** pull-based, append-only log, consumer groups, replay from offset. Better for event
streaming, audit trails, high-throughput fan-out.

### Decision matrix

| Scenario | Use |
|----------|-----|
| User requests case data, needs it now | Sync REST |
| Internal service needs a calculation result immediately | Sync gRPC |
| Triggering background processing after a write | Async MQ |
| Sending a notification | Async MQ |
| Broadcasting an event to multiple consumers | Async Kafka |
| High-throughput log ingestion | Async Kafka |

```
// Node.js analogy
// Sync: await axios.get('/api/cases/CNR123')
// Async: bull.add('summary-job', { hearingId: 42 })

// Spring Boot equivalent
// Sync: webClient.get().uri("/cases/CNR123").retrieve().bodyToMono(...)
// Async: rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event)
```

---

## 4. Service Discovery

### The problem

In a monolith, there is no "service discovery." Everything is a method call.

In microservices: Service A needs to call Service B. But Service B's IP address is not fixed. It
might be `10.0.1.15` right now. After the next deploy, it might be on `10.0.1.22`. In Kubernetes,
pods get new IPs every restart. Hard-coding IPs is not viable.

### The solution: Service Registry

A central registry that services register with. Clients look up services by *name*, not IP.

```
Startup:  AI Summary Service  ─── "I am 'ai-service' at 10.0.1.15:8082" ──► Eureka Registry
                                                                              │
Query:    Scraper Service ─── "Where is 'ai-service'?" ──────────────────► Eureka
                         ◄─── "10.0.1.15:8082" ────────────────────────────
                         │
                         └── calls http://10.0.1.15:8082/summarize
```

### Spring Cloud Netflix Eureka

The Netflix OSS service registry, now part of Spring Cloud.

```java
// On each microservice: add dependency
// spring-cloud-starter-netflix-eureka-client

// In application.yml of each service:
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/
  instance:
    prefer-ip-address: true

// On the Eureka Server itself:
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication { ... }

// On each client service:
@EnableDiscoveryClient  // or just rely on auto-config
@SpringBootApplication
public class ScraperServiceApplication { ... }
```

Services call each other by logical name using `@LoadBalanced` RestTemplate or Spring Cloud
LoadBalancer:

```java
// Client-side load balancing — Eureka provides the instance list
@Bean
@LoadBalanced
public RestTemplate restTemplate() { return new RestTemplate(); }

// Call by service name, not IP:
restTemplate.getForObject("http://ai-summary-service/summaries/{id}", Summary.class, hearingId);
```

### Alternatives

| Option | Context |
|--------|---------|
| **Kubernetes Service** | If you're on K8s, you get service discovery for free via DNS. `http://ai-service.default.svc.cluster.local` resolves to the pod. No Eureka needed. |
| **Consul** | Works on VMs and K8s. Also does distributed config and health checking. |
| **AWS Cloud Map** | AWS-native service discovery, integrates with ECS/EKS. |

In practice: if you're on Kubernetes, don't add Eureka. K8s already solves this. Eureka matters
most in VM-based or mixed environments.

---

## 5. API Gateway

### The problem without a gateway

```
React Frontend calls:
  auth.company.com:8081/login
  cases.company.com:8082/cases/CNR123
  scraper.company.com:8083/scrape
  notifications.company.com:8084/subscribe
```

Problems:
- Frontend knows about every service's URL and port
- CORS must be configured on every service
- SSL termination on every service
- Auth check duplicated in every service
- No single place to add rate limiting

### The solution: API Gateway

```
React Frontend
     │
     ▼
┌─────────────────────────────────────────┐
│           API Gateway :443              │
│                                         │
│  - SSL termination                      │
│  - Auth token validation                │
│  - Rate limiting                        │
│  - Request routing                      │
│  - Load balancing                       │
│  - CORS headers                         │
└──┬──────────┬─────────┬────────────────┘
   │          │         │
   ▼          ▼         ▼
Auth Svc   Case Svc  AI Svc    (internal HTTP, no SSL needed)
:8081      :8082     :8083
```

### Spring Cloud Gateway

The Spring-native API gateway (replaces the older Netflix Zuul).

```yaml
# application.yml of the Gateway service
spring:
  cloud:
    gateway:
      routes:
        - id: case-service
          uri: lb://case-service          # lb:// = load-balanced lookup via Eureka
          predicates:
            - Path=/api/cases/**
          filters:
            - StripPrefix=1
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20

        - id: ai-service
          uri: lb://ai-summary-service
          predicates:
            - Path=/api/summaries/**
```

### Where VaadVivaad would place the gateway

In the current monolith, Spring Security handles auth at the filter level. In a microservices
split, the gateway validates the JWT and passes `X-User-Id` and `X-User-Role` headers to
downstream services. Downstream services trust these headers (since they can only come from the
gateway on internal network).

### Node.js analogy

Nginx as a reverse proxy is the gateway equivalent — it routes `/api/v1` to Express on port 3001
and `/api/v2` to another service. Spring Cloud Gateway is like Nginx but programmable in Java,
with load balancing, circuit breaking, and filter chains built in.

---

## 6. Circuit Breaker Pattern

### The problem

VaadVivaad calls the Claude API synchronously from `ClaudeClient.complete()`:

```java
ClaudeResponse response = claudeWebClient.post()
    .uri(MESSAGES_PATH)
    .bodyValue(request)
    .retrieve()
    .bodyToMono(ClaudeResponse.class)
    .timeout(Duration.ofSeconds(timeoutSeconds))
    .block();
```

If Anthropic's API is having an outage:
- Every call waits 30 seconds, then times out
- Threads pile up waiting
- Memory fills with queued requests
- **The entire application degrades** because of one external dependency

This is the cascading failure problem.

### The circuit breaker analogy

Think of an electrical circuit breaker in a fuse box. When too much current flows (fault detected),
it flips open and stops the current. You reset it manually once the problem is fixed.

Software circuit breaker — same idea:

```
         CLOSED (normal)
         ┌─────────────┐
         │ Calls pass  │
         │ through     │
         └──────┬──────┘
                │  N failures within window
                ▼
         OPEN (failing fast)
         ┌─────────────┐
         │ Calls fail  │
         │ immediately │  ← No waiting 30s. Returns fallback instantly.
         │ (fallback)  │
         └──────┬──────┘
                │  After timeout (e.g. 60s)
                ▼
         HALF-OPEN (testing)
         ┌─────────────┐
         │ Lets through│
         │ 1 probe call│
         └──────┬──────┘
                │  Success → CLOSED again
                │  Failure → back to OPEN
```

### State transitions in detail

| State | What happens | Transition |
|-------|-------------|------------|
| **CLOSED** | All calls go through. Failures counted. | After `failureRateThreshold` % failures in `slidingWindowSize` calls → OPEN |
| **OPEN** | All calls fail immediately. Fallback executed. No wait. | After `waitDurationInOpenState` → HALF_OPEN |
| **HALF_OPEN** | Limited calls permitted (`permittedNumberOfCallsInHalfOpenState`). | Success → CLOSED. Failure → OPEN |

---

## 7. Resilience4j in Spring Boot

Resilience4j is the standard resilience library for Spring Boot 3.x (replaced Hystrix which is
EOL).

### Adding the dependency

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
<!-- Also needed for @CircuitBreaker annotation support -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### Circuit Breaker on ClaudeClient

```java
// If you extracted an interface from ClaudeClient:
@Component
public class ClaudeClient {

    // Current code (no circuit breaker):
    public String complete(String prompt) {
        // ... direct WebClient call ...
    }
}

// With Resilience4j:
@Component
public class ClaudeClient {

    @CircuitBreaker(name = "claude", fallbackMethod = "completeFallback")
    @TimeLimiter(name = "claude")       // enforce timeout at circuit breaker level too
    public String complete(String prompt) {
        return claudeWebClient.post()
            .uri(MESSAGES_PATH)
            .bodyValue(buildRequest(prompt))
            .retrieve()
            .bodyToMono(ClaudeResponse.class)
            // Note: with TimeLimiter, use CompletableFuture instead of .block()
            .toFuture()
            .thenApply(ClaudeResponse::extractText);
        // Returns CompletableFuture<String> when combined with @TimeLimiter
    }

    // Fallback — same signature + Throwable parameter
    public String completeFallback(String prompt, Throwable ex) {
        log.warn("Claude API unavailable ({}). Returning fallback summary.", ex.getMessage());
        return "Summary generation is temporarily unavailable. Please check back later.";
    }
}
```

Configuration in `application.yml`:

```yaml
resilience4j:
  circuit-breaker:
    instances:
      claude:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10           # Last 10 calls
        failure-rate-threshold: 50        # 50% failures → OPEN
        wait-duration-in-open-state: 60s  # Stay OPEN for 60s before testing
        permitted-number-of-calls-in-half-open-state: 3
        minimum-number-of-calls: 5        # Need at least 5 calls before calculating

  time-limiter:
    instances:
      claude:
        timeout-duration: 30s

  retry:
    instances:
      ecourts:
        max-attempts: 3
        wait-duration: 2s
        retry-exceptions:
          - com.vaadvivaad.scraper.exception.ScraperException
```

### Retry on ECourtWebClient

eCourts sometimes returns 503 or times out. Retry makes sense here:

```java
@Component
public class ECourtWebClient {

    @Retry(name = "ecourts", fallbackMethod = "fetchCaseHtmlFallback")
    public String fetchCaseHtml(String cnrNumber) {
        // existing implementation
    }

    public String fetchCaseHtmlFallback(String cnrNumber, Throwable ex) {
        log.error("eCourts unreachable after retries for CNR {}: {}", cnrNumber, ex.getMessage());
        throw new ScraperException("eCourts unavailable after retries: " + ex.getMessage(), ex);
    }
}
```

Configuration:

```yaml
resilience4j:
  retry:
    instances:
      ecourts:
        max-attempts: 3
        wait-duration: 2s
        exponential-backoff-multiplier: 2   # 2s, 4s, 8s
        retry-exceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException.ServiceUnavailable
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.vaadvivaad.scraper.exception.ScraperException  # don't retry our own wrapped errors
```

### All Resilience4j annotations

| Annotation | Purpose | VaadVivaad use case |
|-----------|---------|-------------------|
| `@CircuitBreaker` | Open/close circuit on failure rate | Claude API outages |
| `@Retry` | Retry N times with delay | eCourts transient failures |
| `@RateLimiter` | Max N calls per time window | Prevent eCourts from blocking us |
| `@Bulkhead` | Limit concurrent calls | Cap Claude API parallelism |
| `@TimeLimiter` | Timeout with CompletableFuture | Claude API 30s timeout |

```java
// RateLimiter — VaadVivaad eCourts scraper is "polite" (Thread.sleep(2000))
// A proper RateLimiter replaces the Thread.sleep:
@RateLimiter(name = "ecourts")
public String fetchCaseHtml(String cnrNumber) { ... }

# Config:
resilience4j:
  rate-limiter:
    instances:
      ecourts:
        limit-for-period: 1           # 1 request
        limit-refresh-period: 2s      # per 2 seconds
        timeout-duration: 0           # fail immediately if rate exceeded
```

---

## 8. Distributed Transactions — The Hard Problem

### Why ACID breaks at service boundaries

In VaadVivaad as a monolith, `@Transactional` is magical. PostgreSQL guarantees that multiple
table writes either all commit or all roll back. Hibernate manages a single connection and
transaction context.

In microservices: Service A writes to DB-A. Service B writes to DB-B. These are separate database
connections, separate database servers. There is no single transaction coordinator.

```
Case Service                        AI Summary Service
     │                                    │
     ├── DB-A (court_cases, hearings)     ├── DB-B (hearing_summaries)
     │                                    │
     └── writes case ✓                    └── writes summary ✓

What happens if Case Service commits but AI Service fails?
→ You have a case with no summary, and no way to know what happened.
```

### Two-Phase Commit (2PC) — exists, rarely used

A coordinator asks all participants to "prepare" (lock, but don't commit). Then asks all to
"commit" if everyone said ready.

```
Coordinator: "Prepare to commit?"
Case Svc:   "Ready ✓"
AI Svc:     "Ready ✓"

Coordinator: "Commit!"
Both commit.

--- On failure ---
Coordinator: "Prepare to commit?"
Case Svc:   "Ready ✓"
AI Svc:     "CRASH"

Coordinator: "Rollback all!"
Case Svc rolls back.
```

Problems with 2PC:
- **Slow.** Two network round trips minimum.
- **Coordinator is a Single Point of Failure.** If coordinator crashes between "prepare" and
  "commit," participants are in limbo — locks held indefinitely.
- **Tight coupling.** All services must implement the 2PC protocol.

In practice: 2PC is used in XA transactions (JTA, Atomikos) in enterprise Java, but almost never
in modern microservices. The industry has moved to eventual consistency.

### Eventual consistency

Accept that the system may be temporarily inconsistent, but will converge to a consistent state
given enough time (and no further failures).

```
t=0: Case saved. Summary not yet generated.
t=5s: Summary request event published.
t=10s: AI Service processes event. Summary saved.

At t=3s: case is visible, summary is null → consistent by t=10s.
```

This is the trade-off VaadVivaad already makes. Case data is immediately available. Summary
appears asynchronously.

---

## 9. Saga Pattern

A saga is a sequence of local transactions. Each local transaction is ACID within its own service.
The saga as a whole achieves eventual consistency by chaining these transactions via events.

### Choreography-based Saga (what VaadVivaad already does)

No central coordinator. Each service listens for events and reacts.

```
ScraperService:
  1. Scrape eCourts → parse HTML
  2. BEGIN @Transactional
  3.   Save CourtCase to DB
  4.   Delete old Hearings
  5.   Save new Hearings
  6.   publish SummaryRequestEvent to RabbitMQ
  7. COMMIT

AI Consumer (RabbitListenerService):
  8. Receive SummaryRequestEvent
  9. Fetch Hearing by ID
  10. Build prompt
  11. Call Claude API
  12. Save summary to DB

NotificationScheduler:
  13. @Scheduled 8 AM
  14. Find tomorrow's hearings
  15. publish HearingReminderEvent per subscription

NotificationConsumer:
  16. Receive HearingReminderEvent
  17. Send WhatsApp/SMS
```

This is a choreography saga. VaadVivaad is event-driven (steps 6→8 and 15→16 are async via MQ).

**What happens on failure?** The current `ScraperService` code acknowledges this:

```java
/*
 * WHY inside @Transactional?
 * If RabbitMQ publish fails, the transaction rolls back and
 * we don't have orphaned hearings with no summary request.
 * If the transaction rolls back after publish — the consumer
 * will try to fetch a non-existent hearing ID and log a warning.
 * That is acceptable for MVP. Production would use transactional
 * outbox pattern to guarantee exactly-once delivery.
 */
```

The comment *in the actual VaadVivaad code* is an interview answer. Know what the transactional
outbox pattern is.

### Transactional Outbox Pattern

Solves the problem of publishing MQ messages inside a DB transaction:

```
Instead of publishing directly to RabbitMQ inside the transaction:

1. Write to DB + write to `outbox` table in SAME transaction
   (outbox: { event_type, payload, published: false })

2. A background job (Debezium, polling) reads the outbox table
3. Publishes unprocessed events to RabbitMQ
4. Marks them as published
```

This guarantees: if the DB commits, the event will eventually be published. No phantom messages
(publish then rollback).

### Compensating Transactions

When a saga step fails, you need to *undo* previous steps. Since you can't rollback across
services, you compensate.

```
Saga: Scrape → Save Case → Save Hearings → Request Summary

If "Request Summary" fails:
  Compensation: Mark hearings as "summary_failed" (not delete — maybe user still wants the case)

If "Save Hearings" fails:
  Compensation: Delete the CourtCase that was saved (or mark as incomplete)
```

In VaadVivaad's current code: `@Transactional` handles steps 1-3 together. If anything in that
transaction fails, they all roll back — no compensation needed within the transaction. Compensation
would only be needed for cross-service rollback.

### Orchestration-based Saga

A central Saga Orchestrator coordinates all steps, calling each service in sequence.

```
┌─────────────────────────────────────┐
│         Saga Orchestrator           │
│  1. Call Scraper Service            │
│  2. Call Case Service (save case)   │
│  3. Call AI Service (generate sum.) │
│  4. On any failure: compensate      │
└─────────────────────────────────────┘
```

Pros: easier to see the full flow in one place. Cons: the orchestrator is a SPOF and knows too
much about other services.

**Use choreography** for simple, linear event chains (like VaadVivaad).
**Use orchestration** for complex flows with branching, parallel steps, and complex compensation.

---

## 10. Event Sourcing

### What it is

Instead of storing the *current state* of an entity, store the *sequence of events* that produced
that state. Reconstruct current state by replaying events.

```
Traditional (what VaadVivaad does):
  court_cases table row: { cnr: "MHPN...", status: "PENDING", last_scraped: "2026-04-03" }

Event Sourced:
  court_case_events:
    { event: "CaseCreated",  timestamp: 2026-01-01, cnr: "MHPN..." }
    { event: "HearingAdded", timestamp: 2026-01-15, hearing_date: "2026-02-10" }
    { event: "HearingRescheduled", timestamp: 2026-02-08, new_date: "2026-02-20" }
    { event: "CaseScraped", timestamp: "2026-04-03", status: "PENDING" }
```

**Benefits:**
- Complete audit trail — you know every state change and when
- Can reconstruct state at any point in time (point-in-time recovery)
- Natural fit for event-driven architecture
- Temporal queries: "what was the status of this case on March 15th?"

**Costs:**
- Complexity: to get current state, replay all events (usually solved with snapshots)
- Schema evolution: if event structure changes, old events are still in the old format
- Storage grows unboundedly

### VaadVivaad is event-driven, NOT event-sourced

VaadVivaad publishes events (`SummaryRequestEvent`, `HearingReminderEvent`) to RabbitMQ as a
communication mechanism. These events are transient — they are not the source of truth. The DB
tables are the source of truth.

Event sourcing would mean: the `court_cases` table does not exist. Instead, there is a
`court_case_events` Kafka topic. To get the current state of a case, you replay all events.
VaadVivaad does not do this, and for an MVP it shouldn't.

**Kafka** is the natural fit for event sourcing (log-compacted topics, replay from offset). Kafka's
retention means events are persistent, not fire-and-forget like RabbitMQ.

---

## 11. CQRS

### Command Query Responsibility Segregation

Separate the **write path** (commands) from the **read path** (queries). Different models for
writing and reading.

```
Write Side:                        Read Side:
  ScraperService writes to           CaseLookupService reads from
  normalized PostgreSQL tables       Redis-cached views or denormalized
  (3NF, ACID, correctness)          read-optimized projections (speed)
       │                                   ▲
       │   event: CaseUpdated              │
       └────────────────────────────────────┘
              (read side subscribes and updates its view)
```

### Why would VaadVivaad benefit from CQRS?

**Write side complexity:** A case scrape involves joins across `court_cases`, `hearings`,
`subscriptions`. Updates are complex.

**Read side requirements:** The dashboard shows: case title, next hearing date, number of
subscribers, last scraped time. This could be served from a pre-computed, denormalized view (one
Redis hash per case) instead of a 3-table JOIN.

**Current approach (without CQRS):**

```java
// CaseLookupService: reads from DB, writes to Redis cache
@Cacheable("cases")
public CourtCase findByCnr(String cnr) {
    return courtCaseRepository.findByCnrNumber(cnr).orElseThrow(...);
}
```

**With CQRS:**

```
Command side:    ScraperService writes CourtCase + Hearings to PostgreSQL
                 Publishes CaseUpdated event

Read Model:      Consumer receives CaseUpdated
                 Builds a CaseSummaryView (flat, denormalized)
                 Stores in Redis as Hash

Query side:      GET /cases/CNR123 → read from Redis directly, no DB join needed
```

### When to reach for CQRS

- Read/write ratio is very unbalanced (many more reads than writes)
- Read and write models have different scaling needs
- Complex queries would benefit from a pre-computed projection

For VaadVivaad MVP: unnecessary. The existing `@Cacheable` on Redis already solves the read
performance concern. CQRS would be the next step at scale.

---

## 12. Distributed Caching

### VaadVivaad's current cache setup

```yaml
# docker-compose.yml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
```

```java
// VaadVivaadApplication.java
@EnableCaching  // activates Spring Cache abstraction backed by Redis

// ScraperService.java
@CacheEvict(cacheNames = "cases", allEntries = true)
public CourtCase scrapeOrRefresh(String cnrNumber) { ... }
```

Redis is a **shared external cache** — if you ran two instances of the Spring Boot app, both would
read from and write to the same Redis. This is correct behavior.

### Distributed cache in microservices

**Option 1: Shared Redis cluster**

```
Case Service ────► Redis Cluster ◄──── Scraper Service
AI Service   ────►               ◄──── Notification Service
```

Simple. All services share cache. Problem: coupling. If Case Service changes its cache key format,
Scraper Service might read stale data from the old format.

**Option 2: Each service owns its own Redis**

```
Case Service ────► Redis-A
Scraper Service ─► Redis-B
```

Better isolation. Cache invalidation is simpler (only Case Service manages its own cache).
Problem: duplication of infra, more cost.

### Cache invalidation across services

The hardest problem in distributed systems (alongside naming and off-by-one errors).

When the Scraper updates case data, it must invalidate the cache that the Case Service owns:

```
Scraper publishes: CaseUpdated { cnr: "MHPN..." }
Case Service consumes: CaseUpdated → evict "cases::MHPN..." from its Redis
```

This is **event-driven cache invalidation**. VaadVivaad already does the local version:
`@CacheEvict` in `ScraperService` evicts the "cases" cache so the next read gets fresh data.

### What to say in interviews

"Cache invalidation across service boundaries is hard because you lose the transactional guarantee
that the DB write and the cache eviction happen atomically. Our approach is event-driven
invalidation: on any write event, the service that owns the cache evicts the affected keys. This
means there is a brief window of stale data, which is acceptable for case status information that
changes at most every few hours."

---

## 13. Health Checks and Readiness

### Spring Boot Actuator endpoints

```yaml
# application.yml (VaadVivaad already configures this)
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

```bash
GET /actuator/health
# Response:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP" },
    "rabbit": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

Spring Boot auto-configures health indicators for any declared beans:
`DataSource` → `DbHealthIndicator`, Redis → `RedisHealthIndicator`, RabbitMQ →
`RabbitHealthIndicator`.

### Liveness vs Readiness — why they are different

| Probe | Question | Action on failure |
|-------|----------|------------------|
| **Liveness** | Is the process alive and not deadlocked? | Kubernetes kills and restarts the pod |
| **Readiness** | Is the app ready to serve traffic? | Kubernetes stops sending traffic to this pod |

```yaml
# Kubernetes pod spec
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

```yaml
# To expose separate liveness/readiness in application.yml:
management:
  endpoint:
    health:
      probes:
        enabled: true   # enables /actuator/health/liveness and /actuator/health/readiness
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

### Why readiness matters for VaadVivaad

At startup, VaadVivaad runs Flyway migrations before the app is ready. During this time,
readiness probe should return DOWN — Kubernetes should not send traffic until migrations complete.
Spring Boot's readiness state automatically handles this: it transitions to ACCEPTING_TRAFFIC only
after all `ApplicationReadyEvent` listeners complete.

If you connect to a DB that's slow to start (common in Docker Compose), the app may start faster
than Postgres. Without a readiness probe, Kubernetes sends traffic to a pod that can't serve it.

### Custom health indicator

```java
// VaadVivaad example: check if eCourts is reachable
@Component
public class ECourtHealthIndicator implements HealthIndicator {

    private final ECourtWebClient eCourtWebClient;

    @Override
    public Health health() {
        try {
            // Simple ping to eCourts
            eCourtWebClient.ping();  // would need to implement
            return Health.up()
                .withDetail("eCourts", "reachable")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("eCourts", "unreachable")
                .withException(e)
                .build();
        }
    }
}
```

---

## 14. Distributed Tracing

### The problem in microservices

A user requests case details. The request chain is:

```
Browser → API Gateway → Case Service → Scraper Service → eCourts
                                    → AI Service → Claude API
```

Something is slow. Which leg? The case service? The Claude call? eCourts?

In a monolith: one stack trace. In microservices: 5 logs in 5 different places, all with
different timestamps and no shared identifier.

### Trace ID and Span ID

A **trace** represents the entire request journey. A **span** represents one leg of the journey.

```
TraceId: abc123
├── Span: API Gateway (0ms - 5ms)
├── Span: Case Service (5ms - 150ms)
│   ├── Span: DB query (5ms - 20ms)
│   └── Span: RabbitMQ publish (140ms - 150ms)
└── Span: AI Service (150ms - 3000ms)   ← the slow one
    └── Span: Claude API call (155ms - 2995ms)
```

### How trace ID propagates

HTTP headers carry the trace context:

```
// Incoming request to Case Service:
X-B3-TraceId: abc123
X-B3-SpanId: def456
X-B3-ParentSpanId: 111000

// Case Service creates a child span and forwards:
X-B3-TraceId: abc123     ← same trace
X-B3-SpanId: ghi789      ← new span for this leg
X-B3-ParentSpanId: def456

// Message queue: trace context is in message headers
```

Modern standard: **OpenTelemetry** (OTEL) — vendor-neutral, language-agnostic.
Replaces Zipkin B3 propagation as the standard format.

### Spring Boot + Micrometer Tracing

(Spring Cloud Sleuth was deprecated in Spring Boot 3.x; replaced by Micrometer Tracing)

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # sample 100% of requests (reduce in production)
  zipkin:
    tracing:
      endpoint: http://zipkin:9411/api/v2/spans
```

Once configured, every log line automatically includes the trace ID:

```
2026-04-03 08:01:23 [abc123,def456] INFO ScraperService - Fetching CNR: MHPN...
2026-04-03 08:01:24 [abc123,def456] DEBUG ClaudeClient - Calling Claude API
2026-04-03 08:01:55 [abc123,def456] DEBUG ClaudeClient - Claude response: 420 chars
```

Now in Zipkin/Jaeger UI, you search by `traceId=abc123` and see the complete waterfall.

### Tying to VaadVivaad

In VaadVivaad's case:
- User triggers scrape for `MHPN01234` → trace starts at the REST controller
- `ScraperService.scrapeOrRefresh()` creates a child span
- `ECourtWebClient.fetchCaseHtml()` creates a child span (the eCourts HTTP call)
- `RabbitMQ publish` creates a child span
- AI consumer picks up the message → inherits the trace (if AMQP propagation configured)
- `ClaudeClient.complete()` creates a child span (the Claude HTTP call)

If the user reports "my summary never appeared," you trace the trace ID from the scrape log and
see: the Claude call returned null at step 7 — the API returned 401 (invalid API key).

---

## 15. 12-Factor App Principles

The [12-Factor App](https://12factor.net) methodology defines how to build software-as-a-service
apps that are portable, scalable, and maintainable. Originally written by Heroku engineers.
VaadVivaad follows most of these naturally.

### The 12 factors, focused on what matters

**1. Codebase** — One codebase, many deploys. VaadVivaad: one Git repo, deployed to dev and prod.

**2. Dependencies** — Explicitly declare. VaadVivaad: Maven `pom.xml` declares every dependency.
No assuming OS-level tools exist.

**3. Config** — Store config in the environment. VaadVivaad `application-prod.yml` does exactly
this:

```yaml
# application-prod.yml — no credentials in code
spring:
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
  rabbitmq:
    host: ${RABBITMQ_HOST}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST}
claude:
  api-key: ${CLAUDE_API_KEY:dummy-key-for-dev}  # dev fallback
```

The `${VARIABLE_NAME}` syntax is Spring's way of reading from environment variables. This is
identical in concept to `process.env.DATABASE_URL` in Node.js.

**4. Backing services** — Treat them as attached resources. DB, Redis, RabbitMQ are all attached
via env vars. Swap the DB URL and you're pointed at a different database — no code change.

**5. Build, Release, Run** — Separate stages. Docker enables this. `docker build` (build) →
tag with version (release) → `docker run` with env vars (run).

**6. Processes** — Stateless. VaadVivaad uses JWT (stateless tokens). No server-side session. Any
instance can serve any request. This is why horizontal scaling works.

**7. Port binding** — Export services via port. Spring Boot's embedded Tomcat binds to port 8080.
No external servlet container needed. `server.port: 8080`.

**8. Concurrency** — Scale out via process model. In K8s: add more pod replicas. Spring Boot
handles concurrent requests via thread pool (Tomcat thread pool, default 200 threads).

**9. Disposability** — Fast startup, graceful shutdown. Spring Boot's graceful shutdown (enabled
by default in Spring Boot 3) waits for in-flight requests before shutting down when it receives
SIGTERM. This is critical for K8s rolling deployments — K8s sends SIGTERM before replacing the
pod.

```yaml
# application.yml
server:
  shutdown: graceful  # wait for in-flight requests
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # max 30s to finish
```

**10. Dev/prod parity** — Keep dev and prod as similar as possible. VaadVivaad's
`docker-compose.yml` runs the same PostgreSQL 16, Redis 7, RabbitMQ 3 as production. No "I used
SQLite locally but prod is Postgres" mismatches.

**11. Logs** — Treat logs as event streams. Write to stdout. Don't manage log files. The platform
(Docker, K8s) collects stdout. Logback in Spring Boot defaults to stdout. In prod, a log
aggregator (ELK, Loki, CloudWatch) collects from all pod stdouts.

**12. Admin processes** — One-off tasks as separate processes. Example: `flyway migrate` is
run on startup (or as a separate init container in K8s). Not a separate admin CLI in VaadVivaad,
but the principle is: admin tasks are versioned, tracked, run in the same environment as the app.

---

## 16. Containerization and Kubernetes

### Docker: the mental model

A Docker image is an immutable snapshot. "Build once, run anywhere." Every time you start a
container from the image, it is identical — same JDK, same JAR, same config. The only things
that vary at runtime are environment variables.

```
# A typical Spring Boot Dockerfile (VaadVivaad backend):
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/vaadvivaad-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
# Environment variables injected at `docker run` time — not baked into image
```

Multi-stage build (good practice):

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline        # cache deps layer
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: runtime — smaller image, no Maven, no source
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Kubernetes concepts (what matters for interviews)

**Pod** — the smallest deployable unit. Usually one container. Ephemeral — it can die and be
replaced.

**Deployment** — manages a set of identical pods. Handles rolling updates, scaling.

```yaml
# VaadVivaad Case Service Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vaadvivaad-backend
spec:
  replicas: 2                    # 2 pods always running
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0          # never go below 2 pods (zero-downtime)
      maxSurge: 1                # can temporarily have 3 pods during update
  selector:
    matchLabels:
      app: vaadvivaad-backend
  template:
    spec:
      containers:
        - name: app
          image: vaadvivaad/backend:1.2.3
          ports:
            - containerPort: 8080
          env:
            - name: DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: vaadvivaad-secrets
                  key: database-url
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
```

**Service** — stable network endpoint that load-balances to pods. Pods come and go; Service IP
stays fixed.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: vaadvivaad-backend
spec:
  selector:
    app: vaadvivaad-backend
  ports:
    - port: 8080
  type: ClusterIP   # internal only; Ingress handles external access
```

**Ingress** — HTTP routing at the cluster edge. Like a gateway for external traffic.

**Horizontal Pod Autoscaler (HPA)** — watches metrics, scales deployments automatically.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: vaadvivaad-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: vaadvivaad-backend
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70   # scale up if avg CPU > 70%
```

### Rolling deploys and graceful shutdown

K8s rolling update:
1. Start new pod with new image
2. Wait for new pod's readiness probe to pass
3. Send SIGTERM to one old pod
4. Old pod's Spring Boot graceful shutdown: stop accepting new requests, finish in-flight (up to
   30s), exit
5. K8s removes the old pod from Service endpoints
6. Repeat for next old pod

This is why `server.shutdown: graceful` and the readiness probe are critical. Without them, K8s
would kill pods mid-request and remove them from load balancing before the new pod is ready.

### VaadVivaad scheduler problem in K8s

`NotificationScheduler` uses `@Scheduled(cron = "0 0 6 * * *")`. With 3 replicas, all 3 fire at
6 AM. The daily re-scrape runs 3 times.

Fix: **ShedLock** — a distributed lock backed by Redis or DB. Only one pod acquires the lock and
runs the job.

```java
@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
@SchedulerLock(name = "reScrapeTrackedCases", lockAtLeastFor = "PT5M", lockAtMostFor = "PT1H")
public void reScrapeTrackedCases() { ... }
```

---

## 17. Service Mesh

A service mesh adds a **sidecar proxy** (Envoy, typically) to every pod. All network traffic
flows through the sidecar, not directly between services.

```
Pod A                           Pod B
┌──────────────────────┐       ┌──────────────────────┐
│ App Container        │       │ App Container        │
│ (Spring Boot :8080)  │       │ (Spring Boot :8080)  │
│          │           │       │      ▲               │
│          ▼           │       │      │               │
│ Envoy Sidecar :15001 │──────►│ Envoy Sidecar :15001 │
└──────────────────────┘       └──────────────────────┘
         ▲                              │
         └──────── Istio Control Plane ─┘
                   (config distribution)
```

**What the mesh gives you:**

- **mTLS automatically** — mutual TLS between every service pair, zero code changes
- **Traffic management** — canary releases (send 10% of traffic to new version), circuit breaking
  at the network level
- **Observability** — metrics, traces, access logs from the sidecar, not the application code
- **Retries and timeouts** — configured at the mesh level via YAML, not in application code

**Istio** is the most common mesh. **Linkerd** is simpler, lighter. **Consul Connect** is a
HashiCorp alternative.

**For VaadVivaad:** a service mesh is out of scope for MVP. It adds significant operational
complexity (the Istio control plane itself is a complex system). Mention it in interviews to
signal awareness. "If we move to microservices on Kubernetes, Istio would give us mTLS, traffic
shaping, and observability without changing application code."

---

## 18. When NOT to Use Microservices

This is the most important section. Knowing when NOT to use a pattern is the senior signal.

### Conway's Law

> "Any organization that designs a system will produce a design whose structure is a copy of the
> organization's communication structure." — Melvin Conway, 1967

Microservices *work* when you have multiple teams who need to deploy independently. The service
boundaries mirror team boundaries. One team owns Auth Service. Another owns the Case Service.
They don't coordinate deploys.

If you have one developer (VaadVivaad), or one small team, you don't have the organizational
pressure that microservices are designed to solve. You're adding the cost without getting the
benefit.

### Don't split what you don't understand

> "You shouldn't start with a microservices architecture. Build a monolith first." — Sam Newman
> (author of "Building Microservices")

VaadVivaad is still discovering requirements:
- Should the scraper retry 3 times or use exponential backoff?
- Should summaries be generated synchronously or always async?
- Should notifications be sent by WhatsApp or email or both?

These questions are still being answered. If you split into microservices now, every refactoring
decision involves coordinating deployments, API versioning, and migration scripts across services.
The monolith lets you move fast.

### Modular monolith first

VaadVivaad's package structure *is* the right step:

```
com.vaadvivaad.scraper       → clean boundary
com.vaadvivaad.ai            → clean boundary
com.vaadvivaad.notification  → clean boundary
com.vaadvivaad.lookup        → clean boundary
```

Each package has its own service classes, repositories, and DTOs. They communicate via interfaces
and events (RabbitMQ), not by reaching into each other's internals. When the time comes to split,
each package becomes a service — the boundary is already there.

### When to actually split

Split a microservice when you have a **specific, concrete scaling or deployment boundary**:

| Signal | Action |
|--------|--------|
| Scraper needs to run 50 CNRs/day and monopolizes CPU | Extract Scraper Service, scale it independently |
| AI Summary costs per token — want to budget separately | Extract AI Service with its own rate limiter |
| Different teams own different modules | Split along team ownership |
| One module deploys 5x per day, others once/week | Split high-velocity module |
| One module needs Python (ML), others need Java | Extract the Python module |

Without these specific signals: keep the monolith.

### The senior answer in interviews

"VaadVivaad is a modular monolith. I could split it into microservices, and here's how I'd do it
[section 2]. But right now it's one developer, one deployment, one operational concern. The
monolith lets me move fast, keep ACID transactions, and avoid the distributed systems complexity
I'd have to manage with microservices. The internal module boundaries are clean — if we needed to
split, the work would be manageable. Premature microservices would be over-engineering."

---

## 19. Node → Java Comparison

| Concept | Node.js | Spring Boot |
|---------|---------|-------------|
| Microservice framework | Express.js | Spring Boot (embedded Tomcat) |
| HTTP client | `axios`, `node-fetch` | `WebClient` (reactive), `RestTemplate` |
| Message queue (publish) | `amqplib`, `ioredis` + Bull | `RabbitTemplate` |
| Message queue (consume) | `amqplib` channel.consume | `@RabbitListener` |
| Circuit breaker | `opossum`, manual | `Resilience4j` (`@CircuitBreaker`) |
| Retry | Custom `async-retry` | `@Retry` (Resilience4j) |
| Service discovery | Consul client NPM | Spring Cloud Netflix Eureka |
| API Gateway | `http-proxy-middleware`, NGINX | Spring Cloud Gateway |
| Health check | `/health` Express route | `/actuator/health` (auto) |
| Process manager | `pm2` (cluster mode) | Kubernetes Deployment (replicas) |
| Env vars | `process.env.DATABASE_URL` | `${DATABASE_URL}` in application.yml |
| Config profiles | `NODE_ENV=production` | `--spring.profiles.active=prod` |
| Graceful shutdown | `process.on('SIGTERM', ...)` | `server.shutdown: graceful` |
| Distributed tracing | `dd-trace`, `opentelemetry-node` | Micrometer Tracing + Zipkin |
| Distributed lock | `redlock` (Redis) | `ShedLock` |
| Job queue (background tasks) | Bull, BullMQ | `@Scheduled` + RabbitMQ consumer |
| Task scheduling | `node-cron`, Bull repeated jobs | `@Scheduled(cron = "...")` |

### Key conceptual differences

**Async model:**
- Node.js: single-threaded event loop. Never block the event loop. `async/await` everywhere.
- Spring Boot: multi-threaded (default 200 Tomcat threads). `WebClient` is non-blocking; `.block()`
  wraps it back to blocking per-thread. One request, one thread (usually).

**RabbitMQ publish comparison:**

```javascript
// Node.js (amqplib)
const channel = await connection.createChannel();
channel.publish(exchange, routingKey, Buffer.from(JSON.stringify(event)));
```

```java
// Spring Boot
rabbitTemplate.convertAndSend(
    RabbitMQConfig.SUMMARY_EXCHANGE,
    RabbitMQConfig.SUMMARY_ROUTING_KEY,
    event  // Jackson2JsonMessageConverter serializes to JSON automatically
);
```

**Consuming messages comparison:**

```javascript
// Node.js (amqplib)
channel.consume(queueName, (msg) => {
    const event = JSON.parse(msg.content.toString());
    processEvent(event);
    channel.ack(msg);
});
```

```java
// Spring Boot
@RabbitListener(queues = RabbitMQConfig.SUMMARY_REQUEST_QUEUE)
public void onSummaryRequest(SummaryRequestEvent event) {
    // Jackson deserializes JSON to SummaryRequestEvent automatically
    summaryService.processRequest(event);
    // ack is automatic on method return (no exception)
    // nack is automatic on unhandled exception → goes to DLQ
}
```

**Circuit breaker comparison:**

```javascript
// Node.js (opossum)
const breaker = new CircuitBreaker(claudeClient.complete, {
    timeout: 30000,
    errorThresholdPercentage: 50,
    resetTimeout: 60000
});
breaker.fallback(() => "Summary unavailable");
```

```java
// Spring Boot (Resilience4j)
@CircuitBreaker(name = "claude", fallbackMethod = "completeFallback")
public String complete(String prompt) { ... }

public String completeFallback(String prompt, Throwable ex) {
    return "Summary unavailable";
}
// Configuration via application.yml — no code changes needed to tune thresholds
```

---

## 20. Senior Interview Q&A

---

**Q1: When would you choose microservices over a modular monolith?**

A: The decision is organizational first, technical second. If separate teams need to deploy
independently without coordinating, microservices enable that. If scaling needs are asymmetric —
one component needs 50 instances, another needs 2 — microservices let you scale them separately.
If technology needs differ — one service runs ML Python, another runs Java — microservices
accommodate that. But if I have one team, uniform scaling needs, and a system I'm still
discovering, I start with a modular monolith. The internal boundaries should still be clean —
packages with clear interfaces — so extraction is feasible when the time comes. VaadVivaad is
exactly this: a modular monolith that could be split, but doesn't need to be yet.

---

**Q2: How would you handle a distributed transaction across three microservices?**

A: I wouldn't try to make it a transaction. I'd use a saga — a sequence of local transactions
coordinated via events. Each service commits its own work locally and publishes an event for the
next step. On failure, compensating transactions undo previous steps. For a linear flow like
VaadVivaad's (scrape → save → generate summary), choreography-based saga works: each service
reacts to events without a central coordinator. For complex branching flows, I'd use an
orchestrator. The key trade-off is that the system is eventually consistent — there's a window
where data is partially complete. You design the domain model to be tolerant of that window.

---

**Q3: A service call to an external API is failing. How do you prevent cascading failures?**

A: Circuit breaker. With Resilience4j, I'd put `@CircuitBreaker(name="external-api",
fallbackMethod="fallback")` on the method. After a configurable failure rate threshold (say 50%
of the last 10 calls), the circuit opens. All subsequent calls return the fallback immediately —
no waiting 30 seconds for a timeout. After 60 seconds, the circuit goes half-open: one probe call
is allowed through. If it succeeds, the circuit closes. If it fails, back to open. This protects
the rest of the system from the failure of one dependency. In VaadVivaad, this is what I'd add to
`ClaudeClient.complete()` — Claude API outages should not cascade into the entire scrape flow.

---

**Q4: How does service discovery work in a Kubernetes cluster?**

A: Kubernetes has built-in DNS-based service discovery. Every Service object gets a DNS name in
the format `<service-name>.<namespace>.svc.cluster.local`. When Pod A wants to call Pod B's
service, it resolves `b-service.default.svc.cluster.local` — Kubernetes DNS returns the Service's
ClusterIP, and kube-proxy load-balances across the pod endpoints. No Eureka needed. The Service
object is the stable endpoint; pods behind it come and go. When a pod dies, the Endpoints object
is updated, and kube-proxy stops routing to the dead pod's IP. The caller's Service gets a
connection refused briefly (which Resilience4j retry handles), then the next pod is used.

---

**Q5: What is the outbox pattern and why does VaadVivaad need it?**

A: VaadVivaad's `ScraperService` publishes a `SummaryRequestEvent` to RabbitMQ *inside* a
`@Transactional` method. There's a race: the transaction commits, then RabbitMQ publish. If the
publish fails, the hearing is saved but no summary will ever be generated — a silent bug. The
outbox pattern fixes this: instead of publishing to RabbitMQ directly, write to an `outbox` table
in the same DB transaction. A separate relay process reads unpublished outbox rows and publishes
them to RabbitMQ, then marks them published. Since DB write and outbox write are atomic (same
transaction), you're guaranteed: "if the case is saved, the summary request will eventually be
published." The relay can be a Spring scheduled job reading from the outbox table, or a Debezium
CDC connector reading from the transaction log.

---

**Q6: What is CQRS and would VaadVivaad benefit from it?**

A: CQRS separates the write model from the read model. Commands (writes) go through the
normalized, ACID-guaranteed DB. Queries (reads) go through a denormalized, optimized read store —
usually a pre-computed projection updated asynchronously from write events. VaadVivaad partially
does this already: `@Cacheable` on Redis is a simple read projection. Full CQRS would mean: when a
case is scraped, publish a `CaseUpdated` event; a read-model consumer rebuilds a flat
`CaseSummaryView` in Redis; all read endpoints hit Redis, not Postgres. This would eliminate the
JOIN overhead for case lookups. VaadVivaad doesn't need full CQRS yet — the Redis cache is
sufficient. But if read throughput became a problem, CQRS is the natural next step.

---

**Q7: How do you debug a slow request that spans multiple microservices?**

A: Distributed tracing. Every request gets a trace ID at the entry point (API gateway or the first
service). This ID is propagated via HTTP headers (`X-B3-TraceId` or OpenTelemetry's
`traceparent`) to every downstream service call and message queue header. Each service creates
spans for its work and reports them to a trace collector (Zipkin, Jaeger). In the UI, I search by
trace ID and see a waterfall — all spans across all services for that one request. I can see which
service took 2.8 seconds out of 3 seconds total. In VaadVivaad, Micrometer Tracing with
Zipkin reporter would give this automatically. Every log line includes the trace ID, so I can
correlate log output with the trace waterfall.

---

**Q8: How does the API Gateway handle authentication in a microservices architecture?**

A: The gateway is the authentication choke point. Every request passes through it. The gateway
validates the JWT: checks signature, checks expiry, extracts user ID and roles. On success, it
passes the identity downstream via internal headers: `X-User-Id: 42` and `X-User-Role: USER`.
Downstream services trust these headers — they don't re-validate the JWT. This is safe because
downstream services are on a private network; only the gateway can inject these headers from
outside. This removes auth logic from every service. The only exception is the auth service itself
(login and registration) which the gateway routes through without validation. In Spring Cloud
Gateway, this is a custom filter bean that runs before routing.

---

**Q9: What happens to VaadVivaad's `@Scheduled` jobs if you run 3 instances?**

A: All 3 instances fire at 8 AM. The daily re-scrape job runs 3 times. Each instance hits eCourts
for every tracked case — tripling the load on eCourts and wasting resources. The fix is
distributed scheduling with ShedLock. ShedLock uses a shared store (Redis or DB) to create a
distributed lock for each job. When the 6 AM cron fires on all 3 instances simultaneously, only
one acquires the lock and runs the job. The others skip it. The lock has a `lockAtMostFor`
duration — if the lock-holding instance crashes mid-job, the lock is automatically released after
that duration, preventing deadlock. This is a direct consequence of moving from single-process to
multi-instance deployment.

---

**Q10: Explain the difference between event-driven architecture and event sourcing.**

A: They're related but distinct. Event-driven architecture (EDA) means services communicate via
events published to a message broker. One service publishes `HearingCreated`; another subscribes
and reacts. Events are ephemeral — once consumed, they're gone from the broker. The database is
still the source of truth. VaadVivaad is event-driven: `SummaryRequestEvent` published to
RabbitMQ, consumed by the AI service. Event sourcing means the event log *is* the source of
truth. You don't store "current state of a hearing" in a row. You store every event that ever
happened to that hearing: `HearingScheduled`, `HearingRescheduled`, `HearingCancelled`.
Current state is derived by replaying events. Event sourcing gives you a complete audit trail and
time-travel queries but adds complexity. VaadVivaad is event-driven but not event-sourced — the
PostgreSQL tables are the source of truth, and RabbitMQ is just the communication channel.

---

## 21. Senior Differentiators

These are the things that distinguish a senior answer from a mid-level answer. Know these cold.

### "I've thought about the failure modes"

Mid-level: "We use RabbitMQ to publish events between services."

Senior: "We publish inside the `@Transactional` boundary. If the DB commits but RabbitMQ publish
fails, we have a lost event. For MVP, that's acceptable — we log it and can re-scrape manually.
For production, the transactional outbox pattern eliminates this window."

The `ScraperService.java` code comments say exactly this. You wrote it. Own it.

---

### "I know why the architecture is the way it is, not just what it is"

Mid-level: "We have two queues: notification.queue and summary.request.queue."

Senior: "We separated the queues because notification delivery and summary generation have
different failure semantics and different consumers. Notification failure should go to a DLQ for
re-delivery; summary failure can be ignored (case still works without a summary). Sharing one
queue would mean one consumer's failure logic affects the other."

---

### "I know the trade-offs I accepted"

Mid-level: "We use `@Transactional` on `scrapeOrRefresh`."

Senior: "The `@Transactional` boundary wraps both the DB writes and the RabbitMQ publish. This
means: if Rabbit publish fails, we roll back the DB write. If the DB commits but Rabbit crashes
between commit and publish, we lose the message. We chose this over the outbox pattern to keep
the MVP simpler. The trade-off: occasional missed summaries in exchange for simpler code. Known,
deliberate choice."

---

### "I know when not to use the pattern"

When asked "why didn't you use microservices?":

"Because VaadVivaad is one developer, one deployment, and the domain is still being discovered.
Microservices would add distributed transaction coordination, service discovery, inter-service
API versioning, and observability infrastructure — all before I know if the core product works.
Modular monolith now, extract services when specific scaling or team boundaries demand it. This
is the same approach Martin Fowler describes in 'Microservice Premium' — the premium only pays
off above a certain complexity threshold."

---

### "I understand the distributed systems guarantees"

Know these terms and what they mean:

| Term | What it means |
|------|--------------|
| **At-most-once delivery** | Message may be lost, never duplicated. Not acceptable for VaadVivaad. |
| **At-least-once delivery** | Message may be duplicated, never lost. RabbitMQ with ack. VaadVivaad uses this. |
| **Exactly-once delivery** | Never lost, never duplicated. Extremely hard. Kafka transactions approximate this. |
| **Idempotency** | Processing the same message twice gives the same result. Required when using at-least-once delivery. |

VaadVivaad's `scrapeOrRefresh` is idempotent: if you call it twice with the same CNR, the second
call finds fresh data and returns it without re-scraping (freshness check). The upsert pattern
ensures no duplicate cases in DB.

---

### The key insight to memorize

> Microservices are an organizational pattern. They solve the problem of multiple teams needing
> independent deployment lifecycles. If you don't have that problem, you're buying all the
> distributed systems complexity without the organizational benefit.

> A modular monolith with clean internal boundaries is not a compromise. It is the correct
> architecture for a system whose requirements are still being discovered by a small team.

---

*End of 13_microservices_concepts.md*
