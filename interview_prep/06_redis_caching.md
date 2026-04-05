# 06 — Redis Caching & Spring Cache Abstraction

**Project:** VaadVivaad — Spring Boot 3.4.4 / Java 21  
**Scope:** Why caching exists, how Spring's abstraction works, deep-dive into VaadVivaad's Redis setup, failure modes, and senior-level interview answers.

---

## Table of Contents

1. [Why Caching Exists](#1-why-caching-exists)
2. [Spring Cache Abstraction](#2-spring-cache-abstraction)
3. [@Cacheable — How It Actually Works](#3-cacheable--how-it-actually-works)
4. [@CacheEvict — Invalidating Stale Data](#4-cacheevict--invalidating-stale-data)
5. [@CachePut — Update Without Skipping Execution](#5-cacheput--update-without-skipping-execution)
6. [Cache Key Strategy and SpEL](#6-cache-key-strategy-and-spel)
7. [TTL — Time To Live](#7-ttl--time-to-live)
8. [RedisConfig Deep Dive](#8-redisconfig-deep-dive)
9. [Cache-Aside Pattern](#9-cache-aside-pattern)
10. [Cache Stampede / Thundering Herd](#10-cache-stampede--thundering-herd)
11. [Cold Start Problem](#11-cold-start-problem)
12. [Stale Data Problem](#12-stale-data-problem)
13. [Multi-Instance Caching](#13-multi-instance-caching)
14. [@Cacheable + @Transactional Interaction](#14-cacheable--transactional-interaction)
15. [Redis Beyond Caching in VaadVivaad](#15-redis-beyond-caching-in-vaadvivaad)
16. [Monitoring Cache Hits and Rates](#16-monitoring-cache-hits-and-rates)
17. [Node.js → Java Comparison](#17-nodejs--java-comparison)
18. [Senior Interview Q&A](#18-senior-interview-qa)
19. [Senior Differentiators](#19-senior-differentiators)

---

## 1. Why Caching Exists

### The Core Problem

Every time a user calls `GET /api/cases/MHNS010123456789`, your service does:

```
HTTP Request
    → CaseLookupService.lookupByCnr()
        → SELECT * FROM court_cases WHERE cnr_number = ?    (DB round-trip 1)
        → SELECT * FROM hearings WHERE court_case_id = ?    (DB round-trip 2)
    → map to CaseResponse
→ HTTP Response
```

This costs roughly **10–50ms per request** for a local PostgreSQL. Fine for 10 users. But 1,000 concurrent users all looking at the same popular case? That's 1,000 redundant reads for *identical* data. You're burning CPU, connection pool slots, and network for information that hasn't changed in 6 hours.

### The Insight

Court case data is **read-heavy and write-rarely**. Cases get scraped every 6 hours at most. Between scrapes, the data is identical. This is the ideal caching candidate.

### The Node.js Analogy You Already Know

You've probably written something like this in Node:

```javascript
// Manual in-memory cache in Node
const caseCache = new Map();

async function lookupByCnr(cnrNumber) {
  if (caseCache.has(cnrNumber)) {
    return caseCache.get(cnrNumber); // cache hit — skip DB
  }
  
  const result = await db.query(
    'SELECT * FROM court_cases WHERE cnr_number = $1', [cnrNumber]
  );
  
  caseCache.set(cnrNumber, result.rows[0]); // store for next time
  return result.rows[0];
}
```

Spring's `@Cacheable` is exactly this pattern — but declarative, backed by Redis, and with TTL and eviction built in. Instead of writing the Map logic yourself, you annotate the method.

---

## 2. Spring Cache Abstraction

### The Big Idea: Separation of Concerns

Spring's cache abstraction separates **what to cache** (your business code) from **how to cache it** (the provider). You write annotations. Spring handles the rest. You can swap Redis for Caffeine for testing without touching a single line of business logic.

### The Three Moving Parts

```
@EnableCaching          ← turns on AOP proxy for cache annotations
    ↓
CacheManager            ← knows which caches exist, how to create them
    ↓
Cache Provider          ← Redis, Caffeine, EhCache, ConcurrentHashMap
```

### Enabling Caching in VaadVivaad

In `VaadVivaadApplication.java`:

```java
@SpringBootApplication
@EnableScheduling
@EnableCaching          // ← this single annotation activates everything
public class VaadVivaadApplication {
    public static void main(String[] args) {
        SpringApplication.run(VaadVivaadApplication.class, args);
    }
}
```

`@EnableCaching` tells Spring to create AOP proxies around any bean method annotated with `@Cacheable`, `@CacheEvict`, or `@CachePut`. Without it, those annotations do nothing — they're just decorations.

### Cache Providers Spring Supports Out of the Box

| Provider | Use Case |
|----------|----------|
| `ConcurrentHashMap` | Default fallback. In-memory. No TTL. Not distributed. |
| `Caffeine` | In-memory with TTL, eviction policies. Great for single-instance. |
| `EhCache` | Enterprise, disk overflow, clustering. Heavy. |
| `Redis` | Distributed, persistent, shared across instances. Production standard. |
| `Hazelcast` | Distributed in-memory grid. Niche. |

VaadVivaad uses Redis because it needs to be deployable across multiple instances (scaling), and because it already has Redis available for other use cases.

### The CacheManager Contract

`CacheManager` is Spring's interface for managing a collection of named caches. When you write `@Cacheable(value = "cases")`, Spring asks the `CacheManager` for a cache named `"cases"`. If it doesn't exist, `CacheManager` creates it with default configuration. You define `CacheManager` as a bean in your `@Configuration` class.

---

## 3. @Cacheable — How It Actually Works

### The Mechanics: Method Interception

`@Cacheable` works through Spring AOP (Aspect-Oriented Programming). When you call a `@Cacheable` method, you're not calling the method directly — you're calling a proxy that wraps it.

```
Caller → Proxy (AOP)
             ↓
         Compute cache key from method parameters
             ↓
         Check Redis: does this key exist?
             ├── YES (cache hit): return cached value immediately
             └── NO  (cache miss): call actual method
                                       ↓
                                   Method executes (DB query runs)
                                       ↓
                                   Store result in Redis under key
                                       ↓
                                   Return result to caller
```

The method body only runs on a cache miss. On a hit, Spring returns the cached value before the method even starts.

### VaadVivaad: @Cacheable in CaseLookupService

```java
// lookup/service/CaseLookupService.java

@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) {
    log.info("Looking up case with CNR: {}", cnrNumber);          // only logs on cache miss

    CourtCase courtCase = courtCaseRepository.findByCnrNumber(cnrNumber)
        .orElseThrow(() -> new ResourceNotFoundException("Court case", "cnrNumber", cnrNumber));

    List<Hearing> hearings = hearingRepository
        .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());

    return mapToResponse(courtCase, hearings);
}

@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "'id:' + #id")
public CaseResponse lookupById(UUID id) {
    CourtCase courtCase = courtCaseRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Court case", "id", id));

    List<Hearing> hearings = hearingRepository
        .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());

    return mapToResponse(courtCase, hearings);
}
```

**What happens on first call to `lookupByCnr("MHNS010123456789")`:**

1. AOP proxy intercepts the call
2. Key computed: `"MHNS010123456789"` (from `#cnrNumber`)
3. Redis lookup: `GET cases::MHNS010123456789` → nil
4. Method executes: 2 DB queries run
5. Result stored: `SET cases::MHNS010123456789 <serialized CaseResponse> EX 3600`
6. Result returned to caller

**What happens on second call with same CNR:**

1. AOP proxy intercepts
2. Key computed: `"MHNS010123456789"`
3. Redis lookup: `GET cases::MHNS010123456789` → hit
4. Deserialized and returned immediately — no DB touch

### The Log Line Tells You Everything

Notice the `log.info("Looking up case with CNR: {}", cnrNumber)` inside the method. In production, if caching is working correctly, you should see this log line far less frequently than your HTTP request count. If every request generates this log, your cache is being evicted or not working.

### What Gets Cached: The Return Value

`@Cacheable` caches the method's **return value**, not inputs. Whatever the method returns — a DTO, an entity, a list — gets serialized and stored. In VaadVivaad, `CaseResponse` (a Java record) gets serialized to JSON via `GenericJackson2JsonRedisSerializer`.

### What @Cacheable Will NOT Cache

- Methods on the same class (self-invocation bypasses AOP proxy)
- `void` return types (nothing to cache)
- Null values (VaadVivaad has `disableCachingNullValues()` — see RedisConfig)
- Private methods (Spring AOP uses proxy-based interception, only works on public methods)

---

## 4. @CacheEvict — Invalidating Stale Data

### The Problem @CacheEvict Solves

Once you cache data, you have a new problem: the cache might have old data. When the DB changes, Redis needs to know. `@CacheEvict` removes cache entries so the next read hits the DB and re-populates the cache with fresh data.

### VaadVivaad: Two Eviction Points

**1. When a case is manually created:**

```java
// CaseLookupService.java

@Transactional
@CacheEvict(value = "cases", allEntries = true)
public CaseResponse createCase(CreateCaseRequest request) {
    // ... creates and saves case to DB
    // After method runs: entire "cases" cache is cleared
}
```

**2. When the scraper refreshes a case:**

```java
// ScraperService.java

@Transactional
@CacheEvict(cacheNames = "cases", allEntries = true)
public CourtCase scrapeOrRefresh(String cnrNumber) {
    // ... scrapes eCourts, updates DB
    // After method runs: entire "cases" cache is cleared
}
```

### Why `allEntries = true` Here?

Both evictions use `allEntries = true`, which nukes the entire `"cases"` cache. This seems aggressive — why not evict just the specific CNR that changed?

**The reason:** VaadVivaad has two cache keys for the same logical case:
- `cases::MHNS010123456789` (by CNR number)
- `cases::id:550e8400-e29b-41d4-a716-446655440000` (by UUID)

Both point to the same data. If you scraped a case and only evicted the CNR key, the ID-keyed entry would still return stale data. With `allEntries = true`, both entries are cleared atomically.

**The trade-off:** On the next request after a scrape, *all* cases get a cache miss temporarily (cold cache). For low-traffic systems like VaadVivaad, this is acceptable. For high-traffic, you'd evict specific keys.

### Targeted Eviction (When You Know the Key)

```java
// Evict only one specific entry
@CacheEvict(value = "cases", key = "#cnrNumber")
public void someMethod(String cnrNumber) { ... }

// Evict by CNR AND by ID
@Caching(evict = {
    @CacheEvict(value = "cases", key = "#cnrNumber"),
    @CacheEvict(value = "cases", key = "'id:' + #id")
})
public void updateCase(String cnrNumber, UUID id) { ... }
```

### When Does Eviction Happen?

By default, `@CacheEvict` fires **after** the method successfully completes. This is the safe default — you don't want to evict the cache if the DB write fails (you'd have an empty cache and an unchanged DB, causing unnecessary DB load on next read).

You can change this with `beforeInvocation = true`:

```java
@CacheEvict(value = "cases", allEntries = true, beforeInvocation = true)
```

Use `beforeInvocation = true` only when you want to guarantee the cache is cleared even if the method throws an exception. Rarely needed.

---

## 5. @CachePut — Update Cache Without Skipping Execution

### The Problem

`@Cacheable` skips the method if the cache hits. But what if you *want* the method to always run (e.g., an update operation) but you *also* want the cache to reflect the new data immediately?

Enter `@CachePut`: the method **always executes**, and the return value is **always stored** in cache.

```java
// Hypothetical update method in VaadVivaad
@Transactional
@CachePut(value = "cases", key = "#cnrNumber")
public CaseResponse updateCase(String cnrNumber, UpdateCaseRequest request) {
    CourtCase courtCase = courtCaseRepository.findByCnrNumber(cnrNumber)
        .orElseThrow(...);
    
    courtCase.setStatus(request.status());
    CourtCase saved = courtCaseRepository.save(courtCase);
    
    return mapToResponse(saved, ...);
    // Return value is stored in cache — next @Cacheable call hits cache not DB
}
```

### @Cacheable vs @CachePut vs @CacheEvict Summary

| Annotation | Method Executes? | Cache Updated? | Use Case |
|------------|------------------|----------------|----------|
| `@Cacheable` | Only on miss | Yes (on miss) | Read operations |
| `@CachePut` | Always | Yes (always) | Update operations |
| `@CacheEvict` | Yes | Cleared | Delete/create operations |

### Why VaadVivaad Uses @CacheEvict Instead of @CachePut on Scrape

After a scrape, `ScraperService.scrapeOrRefresh()` returns a `CourtCase` entity, not a `CaseResponse` DTO. The cache stores `CaseResponse`. Using `@CachePut` here would require building the response in `ScraperService`, leaking presentation concerns into the scraper. `@CacheEvict` (clear all) keeps responsibilities clean.

---

## 6. Cache Key Strategy and SpEL

### What Is SpEL?

SpEL (Spring Expression Language) is Spring's own expression language, used inside annotation attributes. In cache annotations, it's used to dynamically compute cache keys from method parameters.

### Default Key: When You Don't Specify

If you don't specify `key`, Spring uses all method parameters combined. For a method `lookupByCnr(String cnrNumber)`, the default key would be the CNR string itself. This is often fine, but explicit is better.

### VaadVivaad's Key Patterns

```java
// Key = the cnrNumber parameter value directly
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) { ... }
// Redis key: cases::MHNS010123456789

// Key = literal "id:" prefix + UUID as string
@Cacheable(value = "cases", key = "'id:' + #id")
public CaseResponse lookupById(UUID id) { ... }
// Redis key: cases::id:550e8400-e29b-41d4-a716-446655440000
```

Note the single quotes around `'id:'` — in SpEL, string literals need single quotes. `#id` refers to the method parameter named `id`.

### Why the `'id:'` Prefix?

Without it, if a UUID happened to look like a CNR number (unlikely but theoretically possible), the two lookup methods would collide on the same key. The prefix ensures `lookupByCnr` and `lookupById` never overlap.

### Common SpEL Key Patterns

```java
// Parameter value
key = "#userId"

// Method parameter field
key = "#request.cnrNumber"    // request is an object, cnrNumber is its field

// Method name + parameter (namespace by method)
key = "#root.methodName + ':' + #id"

// Conditional caching: only cache if id is not null
@Cacheable(value = "cases", key = "#id", condition = "#id != null")

// Conditional: only cache if result is not empty (unless = evaluated on return value)
@Cacheable(value = "cases", key = "#cnr", unless = "#result == null")

// Composite key from multiple params
key = "#courtId + ':' + #caseType"
```

### SpEL Root Object Properties

| Expression | Meaning |
|------------|---------|
| `#root.method` | The cached method |
| `#root.methodName` | Method name as string |
| `#root.target` | The target object (the service instance) |
| `#root.args[0]` | First method argument (by index, not name) |
| `#result` | The return value (only usable in `@CachePut` and `unless` in `@Cacheable`) |

---

## 7. TTL — Time To Live

### What TTL Means

TTL (Time To Live) is how long a cache entry lives before Redis automatically deletes it. After TTL expires, the key is gone — the next request is a cache miss and the method runs again to re-populate the cache.

### VaadVivaad's TTL: 1 Hour

Configured in `RedisConfig.java`:

```java
RedisCacheConfiguration config = RedisCacheConfiguration
    .defaultCacheConfig()
    .entryTtl(Duration.ofHours(1))   // ← all caches default to 1 hour TTL
    // ...
```

This means every cached `CaseResponse` lives for 1 hour in Redis. After an hour, it expires. The scraper has a 6-hour freshness threshold, so there's a relationship here:

```
Case scraped at T=0
    → Cache populated at T=0 (TTL: 1 hour)
    → Cache expires at T=1h
    → DB still has fresh data (scraper threshold: 6h)
    → Next request at T=1h: cache miss → reads from DB → re-caches
    → Case eligible for re-scrape at T=6h
```

The 1-hour TTL means the cache auto-cleans itself even if `@CacheEvict` is never called. This is a safety net against stale entries accumulating.

### Per-Cache TTL Configuration

If you want different caches to have different TTLs:

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
        .defaultCacheConfig()
        .entryTtl(Duration.ofHours(1));  // fallback for any cache not listed below

    Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
    
    // Court cases: fresh for 1 hour
    cacheConfigs.put("cases", defaultConfig.entryTtl(Duration.ofHours(1)));
    
    // User profiles: rarely change, cache longer
    cacheConfigs.put("users", defaultConfig.entryTtl(Duration.ofHours(24)));
    
    // Search results: short TTL, these need freshness
    cacheConfigs.put("searchResults", defaultConfig.entryTtl(Duration.ofMinutes(5)));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withInitialCacheConfigurations(cacheConfigs)
        .build();
}
```

VaadVivaad currently uses the same 1-hour TTL for everything. In a real production system, you'd tune per-cache.

### What Happens When TTL Expires

1. Redis deletes the key automatically (no intervention needed)
2. Next request is a cache miss
3. `@Cacheable` method executes, fetches from DB
4. New entry stored in Redis with fresh TTL

No data is lost — the DB is always the source of truth. The cache is a disposable optimization layer.

---

## 8. RedisConfig Deep Dive

Here is VaadVivaad's complete `RedisConfig.java`:

```java
@Configuration
public class RedisConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        ObjectMapper objectMapper = new ObjectMapper();

        // 1. Handle Java 8+ date types (LocalDate, LocalDateTime, etc.)
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 2. Store full type info with each cached value
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY
        );

        // 3. Create the serializer
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // 4. Build the cache configuration
        RedisCacheConfiguration config = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(serializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
```

### Breaking It Down

**Why a custom `ObjectMapper` instead of the Spring-managed one?**

The application's main `ObjectMapper` (configured via `spring.jackson.*` in `application.yml`) does NOT include type information. Adding `activateDefaultTyping` to it would break your regular API JSON responses (they'd start emitting `@class` fields everywhere). So `RedisConfig` creates its own isolated `ObjectMapper` specifically for Redis serialization.

**`JavaTimeModule` and `WRITE_DATES_AS_TIMESTAMPS = false`:**

`CaseResponse` contains `LocalDate` and `LocalDateTime` fields (filing date, hearing date, last scraped at). By default, Jackson serializes these as numeric timestamps (e.g., `1711929600`). With `JavaTimeModule` registered and timestamps disabled, they become ISO strings (`"2024-04-01"`). This makes the Redis data human-readable when you inspect it with `redis-cli`.

**`activateDefaultTyping` — The Critical One:**

When Spring deserializes from Redis, it needs to know what class to instantiate. Without type info, it can't know whether a JSON object `{"id": "..."}` is a `CaseResponse`, a `UserDTO`, or a `Map`. 

`activateDefaultTyping` with `JsonTypeInfo.As.PROPERTY` embeds the full class name into the JSON:

```json
{
  "@class": "com.vaadvivaad.lookup.dto.CaseResponse",
  "id": "550e8400-...",
  "cnrNumber": "MHNS010123456789",
  ...
}
```

Now Spring knows exactly what to deserialize into. This is why `ObjectMapper.DefaultTyping.EVERYTHING` is used — `CaseResponse` is a Java record (effectively `final`), and `NON_FINAL` wouldn't cover it.

**`StringRedisSerializer` for keys:**

Cache keys are plain strings (like `cases::MHNS010123456789`). Using `StringRedisSerializer` keeps them human-readable in Redis. Avoid using the Java serializer for keys — the binary mess is undebuggable.

**`disableCachingNullValues()`:**

If `lookupByCnr` throws a `ResourceNotFoundException`, it doesn't return null — it throws. But if a method returns `null`, caching it would mean future callers get a `null` back from cache and can't distinguish "not found" from "not cached yet." Disabling null caching prevents this footgun.

### RedisTemplate vs StringRedisTemplate

These are not related to `CacheManager` but come up in interviews:

```java
// RedisTemplate<K, V>: general purpose, any type for key and value
@Autowired
private RedisTemplate<String, Object> redisTemplate;

redisTemplate.opsForValue().set("key", someObject);
Object result = redisTemplate.opsForValue().get("key");

// StringRedisTemplate: specialized for String key AND String value
@Autowired
private StringRedisTemplate stringRedisTemplate;

stringRedisTemplate.opsForValue().set("counter", "42");
String count = stringRedisTemplate.opsForValue().get("counter");
```

VaadVivaad doesn't directly use `RedisTemplate` — it relies entirely on the `CacheManager` abstraction via `@Cacheable`. But if you needed to implement rate limiting or session storage manually, you'd inject `RedisTemplate` directly.

### Why Not Java Serialization?

Java's built-in `ObjectOutputStream` serialization is the default if you don't configure otherwise. Never use it for Redis. Reasons:

1. **Not human-readable**: Binary blobs you cannot inspect with `redis-cli`
2. **Fragile across versions**: Adding a field to `CaseResponse` corrupts all existing cache entries
3. **Security risk**: Java deserialization is historically exploitable (gadget chain attacks)
4. **No cross-language compatibility**: Python/Node clients can't read it

JSON serialization solves all four problems.

---

## 9. Cache-Aside Pattern

### What It Is

Cache-Aside (also called "Lazy Loading") is the pattern where the **application** controls all interactions with the cache. The cache doesn't know about the DB. The DB doesn't know about the cache. The application coordinates.

```
Read Request:
    Application → check cache
        → hit: return cached value
        → miss: application fetches from DB
                application stores result in cache
                application returns result

Write Request:
    Application → write to DB
    Application → invalidate cache (or update it)
```

This is exactly what `@Cacheable` and `@CacheEvict` implement in VaadVivaad.

### VaadVivaad's Cache-Aside Flow

```
GET /api/cases/MHNS010123456789
    ↓
CaseLookupController.lookupByCnr()
    ↓
[AOP Proxy intercepts]
    ↓ key = "MHNS010123456789"
    ↓
Redis GET cases::MHNS010123456789
    ├─ HIT: deserialize → return CaseResponse (DB never touched)
    └─ MISS:
        ↓
        CaseLookupService.lookupByCnr() executes
            ↓ DB query 1: find court case
            ↓ DB query 2: find hearings
            ↓ map to CaseResponse
        [AOP Proxy stores result]
        ↓
        Redis SET cases::MHNS010123456789 <json> EX 3600
        ↓
        Return CaseResponse
```

### Alternatives to Cache-Aside

| Pattern | Who fetches from DB? | When? |
|---------|---------------------|-------|
| Cache-Aside | Application | On miss |
| Read-Through | Cache itself | On miss (cache has DB adapter) |
| Write-Through | Cache + application | On every write |
| Write-Behind | Cache async | Writes go to cache first, DB later |

**Read-Through**: The cache itself knows how to fetch from DB. Libraries like Caffeine with a loader function. Less code in your service, but the cache layer now has DB knowledge — coupling you usually don't want.

**Write-Through**: Every write goes to cache AND DB simultaneously. No stale data. But doubles write latency. Rarely worth it.

**Write-Behind**: Writes go to cache, DB update happens asynchronously. Lowest write latency, highest risk of data loss if cache crashes before DB sync. Inappropriate for court case data.

Cache-Aside is the right choice for VaadVivaad: simple, application-controlled, and the DB remains the single source of truth.

---

## 10. Cache Stampede / Thundering Herd

### The Problem

Imagine VaadVivaad has 500 users all watching the same high-profile case. The cache entry expires at midnight. At 00:00:00.001, all 500 requests arrive simultaneously. Every single one gets a cache miss. Every single one fires 2 DB queries. You just hit your PostgreSQL with 1,000 queries in one second for the same data.

This is the **cache stampede** (also called thundering herd problem).

```
T=23:59:59  — 500 requests arrive, all get cache HIT (no DB queries)
T=00:00:00  — TTL expires
T=00:00:00  — 500 requests arrive simultaneously
             — All get cache MISS
             — All 500 fire DB queries
             — PostgreSQL: WHERE DID ALL THESE COME FROM
```

### Solutions

**1. Mutex Lock (probabilistic lock)**

Only the first cache-miss request fetches from DB. All others wait:

```java
// Pseudocode — Spring doesn't do this natively
String key = "cases::" + cnrNumber;
String lockKey = key + ":lock";

if (redis.setIfAbsent(lockKey, "1", 5, SECONDS)) {
    // We got the lock — we fetch and populate
    CaseResponse result = fetchFromDb(cnrNumber);
    redis.set(key, result, 1, HOURS);
    redis.delete(lockKey);
    return result;
} else {
    // Someone else is fetching — wait briefly and read from cache
    Thread.sleep(50);
    return redis.get(key); // should be populated by now
}
```

**2. Probabilistic Early Expiry (XFetch algorithm)**

Re-compute the cache entry slightly before it actually expires. If you know TTL is 1 hour, start refreshing at 55 minutes. No thundering herd because the cache is never actually empty:

```java
// Refresh cache when 90% of TTL has elapsed
if (remainingTtl < totalTtl * 0.1) {
    asyncRefresh(key); // background thread re-populates
}
return cachedValue; // still serve the (slightly stale) value
```

**3. Background Refresh with Jitter**

When a cache miss happens, return stale data (if available) and trigger an async refresh. Add random jitter to TTLs so not all entries expire at the same second:

```java
.entryTtl(Duration.ofMinutes(55 + new Random().nextInt(10))) // 55-65 min TTL
```

### What VaadVivaad Does

VaadVivaad has no stampede protection currently — appropriate for an MVP with low traffic. If this came up in interview: "We're aware of the thundering herd problem. At VaadVivaad's current scale it's not an issue, but at higher traffic we'd implement probabilistic TTL jitter or a distributed lock via Redis `SETNX`."

---

## 11. Cold Start Problem

### What It Is

Every time you restart the VaadVivaad backend, Redis loses nothing (Redis persists data), but:

- If Redis itself restarts (e.g., Docker container restart): all cache is gone
- If you switch Redis instances: all cache is gone
- On first deployment ever: cache is empty

All requests hit the DB until the cache warms up organically. This is the **cold start problem**.

### Mitigation: Cache Warming

Pre-populate the cache on application startup. Spring provides `ApplicationRunner` for this:

```java
@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private final CaseLookupService caseLookupService;
    private final CourtCaseRepository courtCaseRepository;

    // constructor injection

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Warming case cache on startup...");

        // Load the most frequently accessed cases
        List<String> popularCnrs = courtCaseRepository.findTopCnrsByAccessCount(100);
        
        for (String cnr : popularCnrs) {
            try {
                caseLookupService.lookupByCnr(cnr); // populates cache as a side effect
            } catch (Exception e) {
                log.warn("Failed to warm cache for CNR: {}", cnr, e);
            }
        }

        log.info("Cache warming complete.");
    }
}
```

### Trade-offs

- Cache warming extends startup time (might cause health check failures if it's too slow)
- You can't know ahead of time which cases will be popular
- Gradual rollout (deploy one instance at a time) lets old instances serve traffic while new one warms up

For VaadVivaad (interview answer): "We rely on organic cache warming currently. On cold start, all requests hit PostgreSQL for the first hour. Given our data size (thousands of cases, not millions), this is acceptable for the MVP."

---

## 12. Stale Data Problem

### The Scenario

```
T=0:00  — Scraper fetches case MHNS01... → stores in DB → populates cache
T=0:10  — User reads MHNS01... → cache hit (correct data)
T=2:00  — Court reschedules hearing — eCourts website updated
T=2:30  — Scraper runs again → detects staleness → fetches from eCourts → updates DB
          @CacheEvict fires → "cases" cache cleared
T=2:30  — User reads MHNS01... → cache MISS (evicted) → reads from DB → fresh data → recaches
```

This is the happy path. The `@CacheEvict` on `scrapeOrRefresh` ensures the stale cache entry is purged when new data arrives.

### What if the Scraper Fails?

```
T=2:30  — Scraper runs → eCourts returns 503 → ScraperException thrown
          @CacheEvict does NOT fire (method threw before completing)
          DB still has old data
          Cache still has old data (but will auto-expire at T=1:00 due to TTL)
T=3:00  — TTL expires → cache entry gone
T=3:01  — User reads → cache miss → reads from DB → still old (scraper failed)
          User gets stale data until the next successful scrape
```

### Why `@CacheEvict` Only Fires on Success

By default, `@CacheEvict` fires **after** the method completes without throwing. If `scrapeOrRefresh` throws a `ScraperException`, the DB is unchanged AND the cache is not evicted. This is correct behavior — you have consistent data (both DB and cache have the same old data).

The TTL is the safety net: even if eviction fails, the cache will auto-expire after 1 hour, and the next read will re-fetch from DB.

### The Two-Layer Freshness Design in VaadVivaad

```
Layer 1 (Redis Cache): TTL = 1 hour
    → Protects DB from repeated reads of the same case
    → Auto-expires, so stale data is bounded to 1 hour max

Layer 2 (Scraper Freshness Threshold): 6 hours
    → Protects eCourts from too-frequent scrapes
    → If data is <6h old in DB, no scrape is triggered
```

These two timers are independent. A case can be:
- In Redis (not expired) AND fresh in DB: ideal, all reads come from cache
- Not in Redis (expired) AND fresh in DB: cache miss, reads DB, re-caches
- Not in Redis AND stale in DB: scraper triggers, DB updated, cache re-populated
- In Redis (not expired) AND stale in DB: won't happen because scraper evicts cache before updating DB

---

## 13. Multi-Instance Caching

### The Problem with In-Memory Cache

Imagine you're using Spring's default `ConcurrentHashMap` cache (no Redis). You deploy 3 instances of VaadVivaad behind a load balancer:

```
Request 1 → Instance A → cache miss → DB query → stored in Instance A's Map
Request 2 → Instance B → cache miss → DB query → stored in Instance B's Map (duplicate!)
Request 3 → Instance C → cache miss → DB query → stored in Instance C's Map (duplicate!)
Request 4 → Instance A → cache HIT (great)
Request 5 → Instance B → cache HIT
...
createCase() called → @CacheEvict on Instance A clears Instance A's Map
                      Instances B and C still have stale data!
```

Each instance has its own in-memory cache. Eviction on one instance doesn't affect others. You'd serve stale data from B and C indefinitely (until their individual TTLs expire, if using Caffeine — ConcurrentHashMap has no TTL).

### Redis Solves This

```
Request 1 → Instance A → Redis miss → DB query → stored in Redis
Request 2 → Instance B → Redis HIT (A already populated it)
Request 3 → Instance C → Redis HIT
createCase() on Instance A → @CacheEvict → Redis entry deleted → 
    next request from any instance is a cache miss → re-fetches from DB
```

Redis is a shared, external store. All instances read and write to the same Redis. `@CacheEvict` on any instance clears the entry for all instances.

### Why This Matters for VaadVivaad

VaadVivaad is designed to be deployed on a single instance (MVP). But the architecture is Redis-backed from day one, so horizontal scaling is possible without changing a line of caching code. This is a design choice worth highlighting in interviews: "We chose Redis over Caffeine specifically because we wanted the option to scale horizontally without refactoring the caching layer."

---

## 14. @Cacheable + @Transactional Interaction

### The Execution Order

```java
@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) { ... }
```

When this method is called through the proxy chain:

**On cache HIT:**
```
AOP proxy intercepts
    → Cache check: HIT
    → Return cached value
    → Transaction is NEVER OPENED
```

**On cache MISS:**
```
AOP proxy intercepts
    → Cache check: MISS
    → @Transactional proxy opens transaction
    → Method body executes (DB queries inside transaction)
    → Transaction commits
    → AOP proxy stores result in cache
    → Return result
```

The cache interceptor runs **before** the transaction interceptor. This is intentional — no point opening a transaction if the cache already has the answer.

### The Proxy Ordering Gotcha

Spring applies AOP advice in order. `@Cacheable` proxy wraps the `@Transactional` proxy wraps the actual method. Conceptually:

```
CacheInterceptor → TransactionInterceptor → Method
```

The cache is checked before any transaction is opened. The result is cached *after* the transaction commits (so you never cache data from a rolled-back transaction).

### The Self-Invocation Problem

This is the most common Spring AOP gotcha. If you call a `@Cacheable` method **from within the same class**, the proxy is bypassed:

```java
@Service
public class CaseLookupService {

    @Cacheable(value = "cases", key = "#cnrNumber")
    public CaseResponse lookupByCnr(String cnrNumber) { ... }

    public void someOtherMethod(String cnrNumber) {
        // THIS WILL NOT USE THE CACHE
        // 'this' refers to the actual object, not the proxy
        CaseResponse r = this.lookupByCnr(cnrNumber); // cache bypassed!
    }
}
```

Fix: inject the service into itself, or move the method to another bean.

```java
@Service
public class CaseLookupService {

    @Autowired
    private CaseLookupService self; // inject the proxy, not 'this'

    public void someOtherMethod(String cnrNumber) {
        CaseResponse r = self.lookupByCnr(cnrNumber); // uses the proxy → cache works
    }
}
```

This is an ugly pattern. Prefer restructuring the code so the cacheable method is in a separate service.

### Don't Cache Within a Long Transaction

```java
// BAD pattern — do not do this
@Transactional
public void processMultipleCases(List<String> cnrNumbers) {
    for (String cnr : cnrNumbers) {
        // This @Cacheable call happens inside an open transaction
        // If you read dirty/uncommitted data from the transaction,
        // it could get cached and served to other requests
        CaseResponse r = caseLookupService.lookupByCnr(cnr);
        // process r...
    }
}
```

If `@Cacheable` is called inside a transaction that later reads uncommitted writes, you risk caching inconsistent data. VaadVivaad avoids this by keeping read operations in `@Transactional(readOnly = true)` with no pending writes in the same transaction.

---

## 15. Redis Beyond Caching in VaadVivaad

Redis is already in VaadVivaad's stack. The infrastructure cost of Redis is paid. Using it for more than caching is a natural expansion:

### 1. Rate Limiting (Sliding Window Counter)

Prevent abuse of the scraper endpoint:

```java
// Using RedisTemplate directly
public boolean allowRequest(String userId) {
    String key = "rate_limit:scraper:" + userId;
    Long count = redisTemplate.opsForValue().increment(key);
    
    if (count == 1) {
        redisTemplate.expire(key, Duration.ofMinutes(1));
    }
    
    return count <= 10; // max 10 scrape requests per minute per user
}
```

### 2. Distributed Locks (for Scraper Coordination)

If multiple instances might scrape the same CNR simultaneously:

```java
String lockKey = "scrape_lock:" + cnrNumber;
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", Duration.ofSeconds(30));

if (Boolean.TRUE.equals(acquired)) {
    try {
        return scrapeFromECourts(cnrNumber);
    } finally {
        redisTemplate.delete(lockKey);
    }
}
// else: another instance is scraping this CNR, skip or wait
```

### 3. Session Storage

If you move from JWT to server-side sessions (or add refresh token revocation):

```java
spring:
  session:
    store-type: redis  # one line to enable Redis-backed sessions
```

### 4. Pub/Sub for Real-Time Updates

Notify connected users when their case gets scraped:

```java
// Publisher (after successful scrape)
redisTemplate.convertAndSend("case_updates", cnrNumber);

// Subscriber (WebSocket handler)
@RedisListener(topics = "case_updates")
public void onCaseUpdate(String cnrNumber) {
    // push notification to connected WebSocket clients
}
```

### 5. Leaderboard / Analytics

Track which CNR numbers are most frequently accessed using Redis sorted sets:

```java
redisTemplate.opsForZSet().incrementScore("popular_cases", cnrNumber, 1);
// Later: get top 10 most accessed cases
Set<String> top10 = redisTemplate.opsForZSet()
    .reverseRange("popular_cases", 0, 9);
```

---

## 16. Monitoring Cache Hits and Rates

### Redis CLI: Basic Inspection

```bash
# Connect to local Redis
redis-cli

# Get all keys in the "cases" cache
KEYS cases::*

# Get a specific cached value
GET "cases::MHNS010123456789"

# Check TTL remaining on a key (seconds)
TTL "cases::MHNS010123456789"

# Count entries in the cases cache
DBSIZE

# Watch commands in real time (development only — high I/O)
MONITOR

# Server stats including hit/miss ratio
INFO stats
# Look for: keyspace_hits, keyspace_misses
```

### Redis INFO Output: Key Metrics

```
# From redis-cli INFO stats
keyspace_hits:1024      ← successful cache reads
keyspace_misses:87      ← cache misses (went to DB)
# Hit rate = 1024 / (1024 + 87) = 92.1%
```

A healthy cache hit rate for VaadVivaad's read-heavy workload should be **>80%**. If it's lower, check:
- Are TTLs too short?
- Is `@CacheEvict(allEntries = true)` called too frequently?
- Is key generation creating too many unique keys?

### Spring Boot Actuator: Cache Endpoint

Add `caches` to actuator exposure in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,caches   # add "caches"
```

Then query:

```
GET /actuator/caches
{
  "cacheManagers": {
    "cacheManager": {
      "caches": {
        "cases": {
          "target": "org.springframework.data.redis.cache.RedisCache"
        }
      }
    }
  }
}

# Evict a specific cache via actuator (DELETE request)
DELETE /actuator/caches/cases
```

### Micrometer Metrics (Production Monitoring)

With `spring-boot-starter-actuator` and Micrometer (both already pulled in by many starters):

```
# Prometheus-compatible metrics
cache.gets{name="cases",result="hit"}   ← counter
cache.gets{name="cases",result="miss"}  ← counter
cache.puts{name="cases"}                ← counter
cache.evictions{name="cases"}           ← counter
```

These export to Prometheus → Grafana for dashboarding. You can alert if hit rate drops below a threshold.

---

## 17. Node.js → Java Comparison

### Manual Node Caching with `ioredis`

```javascript
// Node: manual cache-aside with ioredis
const redis = new Redis({ host: 'localhost', port: 6379 });

async function lookupByCnr(cnrNumber) {
  const cacheKey = `cases:${cnrNumber}`;
  
  // Check cache
  const cached = await redis.get(cacheKey);
  if (cached) {
    return JSON.parse(cached);  // cache hit
  }
  
  // Cache miss: fetch from DB
  const result = await db.query(
    'SELECT * FROM court_cases WHERE cnr_number = $1', [cnrNumber]
  );
  
  // Store with 1 hour TTL
  await redis.setex(cacheKey, 3600, JSON.stringify(result.rows[0]));
  
  return result.rows[0];
}

// Eviction on create
async function createCase(data) {
  await db.query('INSERT INTO court_cases ...', [...]);
  await redis.del(`cases:${data.cnrNumber}`);   // manual eviction
}
```

### Spring @Cacheable Equivalent

```java
// Java: Spring handles all of the above automatically
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) {
    // Write only the business logic — Spring handles Redis interaction
    return courtCaseRepository.findByCnrNumber(cnrNumber)
        .map(this::mapToResponse)
        .orElseThrow(...);
}

@CacheEvict(value = "cases", allEntries = true)
public CaseResponse createCase(CreateCaseRequest request) {
    // Spring evicts automatically after this method completes
}
```

### Comparison Table

| Feature | Node (`ioredis` manual) | Spring (`@Cacheable`) |
|---------|------------------------|-----------------------|
| Cache check | Explicit `redis.get()` | Automatic (AOP proxy) |
| Miss handling | Manual `if (!cached)` | Automatic |
| Store on miss | Explicit `redis.setex()` | Automatic |
| Eviction | Explicit `redis.del()` | `@CacheEvict` |
| TTL | Per `setex()` call | Configured once in `CacheManager` |
| Type safety | Manual `JSON.parse()` | Auto-deserialization |
| Change provider | Rewrite all Redis calls | Change one `@Bean` |
| Testability | Mock `ioredis` | `@EnableCaching(mode=...)` or swap to SimpleCacheManager |

### `node-cache` vs Spring Cache

```javascript
// node-cache: in-memory, single instance, no distributed support
const NodeCache = require('node-cache');
const myCache = new NodeCache({ stdTTL: 3600 });

myCache.set('key', value);
const cached = myCache.get('key');
```

Equivalent to Spring's default `ConcurrentHashMap` provider. No Redis, no distributed support, no TTL management beyond the library's built-in. The Spring equivalent you'd get with no `RedisConfig` bean.

### Key Mental Model Shift

In Node, caching is **infrastructure code you write**. In Spring, caching is **behavior you declare**. The implementation is hidden behind AOP. This is the Java/Spring philosophy: annotate intent, framework handles mechanism.

---

## 18. Senior Interview Q&A

**Q1: How does Spring's `@Cacheable` actually intercept method calls? What's the underlying mechanism?**

Spring uses AOP (Aspect-Oriented Programming) with dynamic proxies. When Spring creates a bean annotated with `@Cacheable` methods, it wraps it in a proxy (either JDK dynamic proxy or CGLIB, depending on configuration). When you call the method, you're calling the proxy's method, which checks the cache first. This is why self-invocation (`this.method()`) bypasses caching — you're calling the real object, not the proxy.

---

**Q2: What's the difference between `@Cacheable`, `@CachePut`, and `@CacheEvict`? When would you use each?**

`@Cacheable`: Read operations. Skips method execution if cache hit. Populates cache on miss. Use for queries that are read-heavy and data doesn't change frequently.

`@CachePut`: Update operations. Method always executes. Return value always stored in cache. Use when you want cache consistency immediately after an update without waiting for the next read to re-populate.

`@CacheEvict`: Write/delete operations. Removes entries from cache. Use when you can't easily compute the new cached value (e.g., a scraper returns an entity, but cache holds a DTO — you'd need to re-fetch and re-map, so it's simpler to just evict and let the next read re-populate).

---

**Q3: In VaadVivaad, why does `@CacheEvict` use `allEntries = true` instead of evicting specific keys?**

The same court case is cached under two keys: one by CNR number (`cases::MHNS01...`) and one by UUID (`cases::id:550e8...`). If a scraper refreshes a case, both entries become stale. Evicting by specific key would require knowing both keys. `allEntries = true` is simpler and guarantees consistency. The trade-off is a temporary cold cache for all cases, but at VaadVivaad's scale that's acceptable.

---

**Q4: Why do we store type information (`@class` field) in Redis, and what's the risk?**

Without type info, Jackson can't deserialize a JSON object back into a specific Java class — it would just produce a `LinkedHashMap`. By embedding the full class name (`"@class": "com.vaadvivaad.lookup.dto.CaseResponse"`), the deserializer knows exactly what to instantiate.

The risk: if you rename or move the class, all existing cache entries become undeserializable. The cached JSON still has the old class name. Spring will throw a `SerializationException` on any read until the old entries expire. Mitigation: during class renames, clear the cache (`redis-cli FLUSHDB` or `DELETE /actuator/caches/cases`) before deploying.

---

**Q5: What is the cache stampede problem and how would you solve it at scale?**

When a cached entry expires and many requests simultaneously get a cache miss, they all query the DB at once, creating a spike. Three solutions: (1) Probabilistic TTL jitter — randomize TTL values so entries don't all expire simultaneously (`TTL = baseTime + random(0, buffer)`). (2) Mutex/distributed lock — only the first miss fetches from DB; others wait briefly then read from the now-populated cache. (3) Background refresh — a background thread re-populates the cache proactively before TTL expires, so the key is never actually empty.

---

**Q6: Explain what happens when a `@Cacheable` method is called from within the same class.**

The cache is bypassed. Spring's AOP proxy is external to the class — it wraps the bean. When you call `this.lookupByCnr()` from inside `CaseLookupService`, you're calling the real object directly, not the proxy. The proxy never intercepts it. Solutions: (1) Inject the service bean into itself (self-injection) to get the proxy reference. (2) Extract the method to a separate service class.

---

**Q7: Why is Java serialization a poor choice for Redis values compared to JSON?**

Four reasons: (1) Not human-readable — you can't `redis-cli GET key` and understand the data. (2) Brittle across versions — adding a field to your class breaks deserialization of all existing cache entries (the serialVersionUID changes). (3) Security — Java deserialization is a historically exploited attack vector. (4) Not interoperable — other clients (Python scripts, dashboards) can't read the binary format.

---

**Q8: How does Spring decide which `CacheManager` to use if you have multiple defined?**

You can have multiple `CacheManager` beans. By default, Spring picks the one named `cacheManager`. For others, annotate them with `@Primary`, or specify explicitly in the annotation: `@Cacheable(value = "cases", cacheManager = "myCustomCacheManager")`. In VaadVivaad, there's exactly one `CacheManager` bean in `RedisConfig`, so no ambiguity.

---

**Q9: How would you test a `@Cacheable` method? How do you disable caching in tests?**

Option 1: Use `@SpringBootTest` with an embedded Redis (Testcontainers):
```java
@SpringBootTest
@Testcontainers
class CaseLookupServiceTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);
}
```

Option 2: Disable caching in tests entirely by overriding the `CacheManager`:
```java
@TestConfiguration
class NoCacheConfig {
    @Bean
    @Primary
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager(); // @Cacheable annotations are no-ops
    }
}
```

Option 3: Use `@SpringBootTest` with a real embedded Redis and verify cache behavior by asserting the method is called only once for multiple identical calls (spy the repository).

---

**Q10: If VaadVivaad scales to 10 instances and each instance can evict the cache, what race conditions could occur?**

Two potential races: (1) **Evict-before-store**: Instance A evicts the cache. Instance B gets a cache miss, starts fetching from DB. Instance A stores the new scraped data in DB and then... nothing (it already evicted). Instance B finishes its DB read and caches the old data it read (before A's DB write committed). Result: cache has stale data briefly. (2) **Double eviction**: Two instances scrape the same case concurrently. Both evict the cache, both write to DB. With proper DB-level locking or unique constraints, the DB write is safe, but you get unnecessary double work. Solution: distributed lock on `"scrape:lock:{cnrNumber}"` so only one instance scrapes a given CNR at a time.

---

## 19. Senior Differentiators

These are the things that separate a candidate who "knows @Cacheable" from one who understands caching deeply.

### Know the Layers

VaadVivaad has three layers of data freshness, each with different purposes:

```
Layer 1: Redis TTL (1 hour)
    Purpose: Protect PostgreSQL from repeated reads
    Owner: CacheManager configuration

Layer 2: Scraper freshness threshold (6 hours)
    Purpose: Protect eCourts from excessive HTTP requests
    Owner: ScraperService.FRESHNESS_HOURS constant

Layer 3: @CacheEvict on write
    Purpose: Ensure consistency when data actually changes
    Owner: @CacheEvict annotations on createCase() and scrapeOrRefresh()
```

A junior knows about layer 1. A senior knows all three and how they interact.

### Know When NOT to Cache

Not everything should be cached:

- **Paginated list endpoints** (`listCases(Pageable pageable)`): VaadVivaad correctly doesn't cache this. The cache key would need to encode page number, page size, sort order — too many variants. And any write invalidates the entire list cache anyway.
- **User-specific data** with high cardinality (one cache entry per user): cache provides little benefit if each entry is accessed only once.
- **Write-heavy data**: the overhead of constant eviction negates the benefits.
- **Large objects**: if your cached value is 1MB and you have 10,000 entries, that's 10GB of Redis memory.

### Know the Serialization Contract

The `@class` annotation in cached JSON creates a deployment contract: don't rename cached classes without also clearing the cache. This is a deployment concern that crosses code and operations — a senior brings this up proactively.

### Know the Consistency Model

Redis cache is **eventually consistent** with the database by design. Between a write and the next cache eviction propagating to all Redis reads, there's a window where some reads return stale data. This is acceptable for VaadVivaad (court hearing data tolerate seconds of staleness). It would NOT be acceptable for financial transactions. Know your consistency requirements before choosing a caching strategy.

### Know the Proxy Rules Cold

The two `@Cacheable` gotchas every interviewer asks about:
1. Self-invocation bypasses the cache
2. `@Cacheable` on a `private` method does nothing

Both stem from the same root cause: Spring AOP uses proxy-based interception and can only intercept calls that go through the proxy.

### The One-Liner That Impresses

"We use Redis as a cache-aside layer with 1-hour TTL and write-invalidation on scrapes. The two-key design for the same logical entity (by CNR and by UUID) necessitates `allEntries = true` eviction rather than targeted eviction. We accept the brief cold-cache cost in exchange for architectural simplicity — a deliberate trade-off for the MVP that we'd revisit if traffic patterns demanded it."

That sentence shows you know the pattern, the key design, the trade-off reasoning, and the scaling consideration. That's what senior-level caching discussion looks like.

---

*Generated for VaadVivaad interview prep — Spring Boot 3.4.4 / Java 21 / Redis*
