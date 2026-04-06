# 07 — WebClient & Reactive Programming
## Senior Interview Prep — VaadVivaad Project

> **Context:** VaadVivaad uses Spring Boot 3.4.4 / Java 21. It is a **blocking Spring MVC
> application** that uses **WebClient** (a reactive API) to make HTTP calls to two external
> services: eCourts government portal and Claude AI. This hybrid approach — blocking app,
> reactive HTTP client — is extremely common and generates excellent interview questions.

---

## Table of Contents

1. [Why WebClient Replaced RestTemplate](#1-why-webclient-replaced-resttemplate)
2. [Reactive Programming — The Mental Model](#2-reactive-programming--the-mental-model)
3. [WebClient.Builder — How Spring Auto-Configures It](#3-webclientbuilder--how-spring-auto-configures-it)
4. [Making HTTP Requests with WebClient](#4-making-http-requests-with-webclient)
5. [Error Handling](#5-error-handling)
6. [Timeout Configuration](#6-timeout-configuration)
7. [Request/Response Logging with ExchangeFilterFunction](#7-requestresponse-logging-with-exchangefilterfunction)
8. [The block() Method — When and Why Not](#8-the-block-method--when-and-why-not)
9. [Retry Logic](#9-retry-logic)
10. [Reactive vs Blocking in Spring Boot — The Hybrid Model](#10-reactive-vs-blocking-in-spring-boot--the-hybrid-model)
11. [Two WebClients in VaadVivaad — Why Separate Beans](#11-two-webclients-in-vaadvivaad--why-separate-beans)
12. [eCourts Scraping Challenges](#12-ecourts-scraping-challenges)
13. [Claude API Integration](#13-claude-api-integration)
14. [Connection Pooling with Reactor Netty](#14-connection-pooling-with-reactor-netty)
15. [Comparing to Node.js](#15-comparing-to-nodejs)
16. [Senior Interview Q&A](#16-senior-interview-qa)
17. [Senior Differentiators](#17-senior-differentiators)

---

## 1. Why WebClient Replaced RestTemplate

### The old way: RestTemplate

`RestTemplate` was Spring's original HTTP client. It is synchronous and blocking — when you
call `restTemplate.getForObject(...)`, the current thread sits idle waiting for the HTTP
response. Under low load, this is fine. Under high load, it becomes a bottleneck: threads
are expensive (typically 1MB of stack each), and a thread pool of 200 threads can only
handle 200 concurrent blocking HTTP calls.

```java
// RestTemplate — old way (still works, but deprecated in Spring 6)
RestTemplate restTemplate = new RestTemplate();
String html = restTemplate.getForObject("https://services.ecourts.gov.in/...", String.class);
// Thread is PARKED here — doing nothing — waiting for the server to respond
// If 200 requests hit simultaneously, 200 threads are all parked
```

`RestTemplate` was deprecated in Spring 5.0 (released 2017) and will be removed in a future
Spring 6.x release. It is **not removed yet** but new code should not use it.

### The new way: WebClient

`WebClient` is non-blocking by default. Under the hood it uses **Reactor Netty**, an
event-loop based HTTP engine. A handful of Netty I/O threads (typically equal to the number
of CPU cores) can manage thousands of concurrent connections — while the network I/O happens,
those threads are free to handle other work.

```java
// WebClient — new way
Mono<String> htmlMono = webClient.get()
    .uri("/ecourtindia_v6/?p=casestatus/index")
    .retrieve()
    .bodyToMono(String.class);
// Nothing has happened yet — htmlMono is a DESCRIPTION of a future operation
```

### Node.js analogy

If you know Node.js, this maps cleanly:

| Java                      | Node.js equivalent                          |
|---------------------------|---------------------------------------------|
| `RestTemplate` (blocking) | `execSync()` / synchronous `fs.readFileSync` |
| `WebClient` (non-blocking) | `axios.get()` / `fetch()` — async I/O       |
| `Mono<T>`                 | `Promise<T>` (not yet awaited)               |
| `.block()`                | `await` (makes it synchronous in context)    |

The critical difference: in Node.js, **everything is non-blocking by default** because the
runtime is single-threaded and would deadlock if it blocked. In Java, you have a choice —
traditional thread-per-request (Tomcat/RestTemplate) or event-loop non-blocking (Netty/WebClient).

---

## 2. Reactive Programming — The Mental Model

### Publisher/Subscriber

Reactive programming is built on the **Publisher/Subscriber pattern**. A `Publisher` produces
data. A `Subscriber` consumes it. Nothing flows until a Subscriber subscribes.

Spring's reactive library is **Project Reactor**, and it has two Publisher types:

| Type       | Items produced | Analogy                          |
|------------|---------------|----------------------------------|
| `Mono<T>`  | 0 or 1         | `Promise<T>` — resolves to one thing |
| `Flux<T>`  | 0 to N         | `AsyncIterator` / observable stream  |

### Lazy evaluation — nothing happens until subscription

This is the most important concept to internalize. In imperative code, calling a method
*does the thing*. In reactive code, calling a method *describes the thing*. Execution only
begins when something subscribes.

```java
// This does NOT make an HTTP request — it describes one
Mono<String> htmlMono = webClient.get()
    .uri("/some/path")
    .retrieve()
    .bodyToMono(String.class);

// THIS triggers the request — subscription happens here
String html = htmlMono.block();  // VaadVivaad uses .block() — more on why later
```

Node analogy:
```javascript
// JavaScript Promise — lazy too, actually
const promise = fetch('https://api.example.com/data');  // fetch starts immediately
// Actually, unlike Reactor, native fetch() IS eager — it starts on creation

// Reactor is more like a factory:
const factory = () => fetch('https://api.example.com/data');
const result = await factory();  // executes only when called
```

### Operator chaining — map, flatMap, filter

Reactive programming transforms data through a **pipeline of operators**:

```java
webClient.get()
    .uri("/cases/{cnr}", cnrNumber)
    .retrieve()
    .bodyToMono(String.class)         // Mono<String>  — raw HTML
    .map(html -> parser.parse(html))  // Mono<ParsedCaseData>  — parsed
    .flatMap(data -> saveToDb(data))  // Mono<CourtCase>  — saved entity
    .doOnSuccess(c -> log.info("Saved: {}", c.getCnrNumber()))
    .doOnError(e -> log.error("Failed", e));
```

- `.map()` — synchronous transform (like `Array.map()` in JS)
- `.flatMap()` — transforms to another Mono/Flux and flattens (like `Promise.then()` returning another Promise)
- `.doOnSuccess()` / `.doOnError()` — side effects (logging, metrics) without changing the value

### The reactive pipeline is a description, not an execution

Think of it like a SQL query: `SELECT * FROM cases WHERE status = 'PENDING'` is a description.
The database executes it when you call it. Similarly, a Reactor pipeline is a description that
Reactor executes on subscription.

---

## 3. WebClient.Builder — How Spring Auto-Configures It

### Spring's auto-configuration

When `spring-webflux` is on the classpath (it comes transitively with `spring-boot-starter-webflux`),
Spring Boot auto-configures a `WebClient.Builder` bean. This builder is **prototype-scoped** —
every time you inject it, you get a fresh copy. This is intentional: you customize your copy
without affecting other WebClients.

```java
// Spring gives you this for free — you DON'T declare it
@Bean
@Scope("prototype")
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}
```

### What you customize on the builder

From `WebClientConfig.java` in VaadVivaad:

```java
// From config/WebClientConfig.java
return WebClient.builder()
    .baseUrl("https://services.ecourts.gov.in")          // 1. Base URL
    .clientConnector(new ReactorClientHttpConnector(httpClient)) // 2. Netty connector with timeouts
    .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 ...")   // 3. Default headers on every request
    .defaultHeader(HttpHeaders.ACCEPT, "text/html,...")
    .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-IN,en;q=0.9")
    .defaultHeader(HttpHeaders.REFERER, "https://services.ecourts.gov.in/ecourtindia_v6/")
    .filter(logRequest())    // 4. Filter chain (logging, auth, retry)
    .filter(logResponse())
    .build();
```

Key builder methods:

| Method                  | Purpose                                                          |
|-------------------------|------------------------------------------------------------------|
| `.baseUrl()`            | Prefix for all relative URIs — keeps call sites clean            |
| `.clientConnector()`    | Swap the underlying HTTP engine (Netty, JDK, Jetty)              |
| `.defaultHeader()`      | Header sent on every request — great for auth, content-type      |
| `.defaultCookie()`      | Cookie sent on every request — useful for session-based scraping |
| `.filter()`             | Middleware — runs on every request/response                       |
| `.codecs()`             | Configure serialization limits (e.g., increase max in-memory size)|

---

## 4. Making HTTP Requests with WebClient

### The HTTP verb methods

```java
webClient.get()       // HTTP GET
webClient.post()      // HTTP POST
webClient.put()       // HTTP PUT
webClient.delete()    // HTTP DELETE
webClient.patch()     // HTTP PATCH
webClient.head()      // HTTP HEAD
webClient.options()   // HTTP OPTIONS
```

### Building the request

```java
webClient.post()
    .uri("/v1/messages")                      // Relative (uses baseUrl) or absolute
    .header("x-api-key", apiKey)              // Per-request header (overrides defaultHeader)
    .contentType(MediaType.APPLICATION_JSON)  // Sets Content-Type header
    .bodyValue(requestObject)                 // Jackson serializes to JSON
    .retrieve()
    .bodyToMono(ClaudeResponse.class)
```

For form data (like eCourts):
```java
// From ECourtWebClient.java — postCnrSearchForm()
MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
formData.add("cino", cnrNumber);
formData.add("captcha_code", captchaSolution);

webClient.post()
    .uri(CNR_SEARCH_PATH)
    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
    .body(BodyInserters.fromFormData(formData))  // NOT .bodyValue() for form data
    .retrieve()
    .bodyToMono(String.class)
```

### `.retrieve()` vs `.exchangeToMono()` — a senior-level distinction

This is a **common interview question**. The difference is critical in production:

**`.retrieve()`** — the safe, recommended default:
```java
webClient.get()
    .uri("/some/path")
    .retrieve()                        // Spring manages the response lifecycle
    .bodyToMono(String.class)          // Response body is consumed and connection returned to pool
```

**`.exchangeToMono()`** (formerly `.exchange()`, now deprecated) — full control, full responsibility:
```java
webClient.get()
    .uri("/some/path")
    .exchangeToMono(response -> {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class);
        } else {
            // YOU must consume the body or the connection leaks
            return response.bodyToMono(String.class)
                .flatMap(body -> Mono.error(new RuntimeException("Error: " + body)));
        }
    })
```

**The connection leak trap:** If you use `.exchange()` (deprecated) or `.exchangeToMono()` and
forget to consume the response body, the Netty connection is **never returned to the pool**.
Under load, the pool drains and new requests hang indefinitely. This is a notoriously hard bug
to find because it only surfaces under concurrent load.

The comments in VaadVivaad's `ECourtWebClient.java` call this out explicitly:

```java
// From ECourtWebClient.java — fetchSearchPage() method:
//
// WHY .retrieve() not .exchange()?
//
// .exchange() gives you full control over the response, but
// YOU must handle the response body — if you forget to consume
// it, you leak connections in the Netty pool.
//
// .retrieve() auto-handles this. Use .exchange() only when
// you need to inspect headers before deciding how to read the body.
//
// This is a COMMON senior interview question about WebClient.
```

### `.bodyToMono(Class)` — deserializing the response

```java
.bodyToMono(String.class)           // Raw string (HTML, text)
.bodyToMono(byte[].class)           // Raw bytes (images, binary)
.bodyToMono(ClaudeResponse.class)   // Jackson deserializes JSON to your class
.bodyToMono(new ParameterizedTypeReference<List<Thing>>() {}) // Generic types
```

From `ClaudeClient.java`:
```java
// Jackson deserializes the Claude JSON response into ClaudeResponse record
ClaudeResponse response = claudeWebClient.post()
    .uri(MESSAGES_PATH)
    .bodyValue(request)                        // ClaudeRequest record -> JSON
    .retrieve()
    .bodyToMono(ClaudeResponse.class)          // JSON -> ClaudeResponse record
    .timeout(Duration.ofSeconds(timeoutSeconds))
    .block();
```

---

## 5. Error Handling

### How reactive error handling works

In a reactive pipeline, errors are just another signal — like a value but representing failure.
Operators handle them without breaking the pipeline contract.

### Key error operators

```java
// .onErrorMap() — transform one exception type to another
.onErrorMap(WebClientResponseException.class, e ->
    new ScraperException("eCourts returned HTTP error: " + e.getStatusCode(), e))

// .onErrorResume() — recover with a fallback value or another Mono
.onErrorResume(TimeoutException.class, e -> Mono.just("default fallback value"))
.onErrorResume(e -> {
    log.warn("Failed, returning empty: {}", e.getMessage());
    return Mono.empty();  // 0 items — subscriber sees completion, not error
})

// .doOnError() — side effect on error, does NOT handle it (error still propagates)
.doOnError(e -> log.error("Request failed: {}", e.getMessage()))
// Same as: try { ... } catch(e) { log.error(...); throw e; }
```

### WebClientResponseException — HTTP 4xx/5xx

When the server returns a non-2xx status, `.retrieve()` automatically throws
`WebClientResponseException` with subclasses for common codes:

```java
// WebClientResponseException hierarchy:
// WebClientResponseException
//   ├── WebClientResponseException.BadRequest (400)
//   ├── WebClientResponseException.Unauthorized (401)
//   ├── WebClientResponseException.Forbidden (403)
//   ├── WebClientResponseException.NotFound (404)
//   ├── WebClientResponseException.TooManyRequests (429)
//   └── WebClientResponseException.InternalServerError (500)
```

### VaadVivaad error handling pattern

In `ECourtWebClient.java`, the reactive chain is wrapped in a try/catch because `.block()`
is called, converting reactive exceptions to synchronous ones:

```java
// From ECourtWebClient.java — fetchCaseHtml()
try {
    String searchPageHtml = fetchSearchPage();       // calls .block() internally
    byte[] captchaImageBytes = fetchCaptchaImage(searchPageHtml);
    String captchaSolution = captchaResolver.solve(captchaImageBytes);
    return postCnrSearchForm(cnrNumber, captchaSolution);

} catch (WebClientResponseException e) {
    log.error("HTTP error fetching eCourts page for CNR {}: {} — {}",
            cnrNumber, e.getStatusCode(), e.getMessage());
    throw new ScraperException(
            "eCourts returned HTTP error: " + e.getStatusCode(), e);

} catch (Exception e) {
    if (e instanceof ScraperException) throw (ScraperException) e;
    throw new ScraperException("Failed to fetch case from eCourts: " + e.getMessage(), e);
}
```

In `ClaudeClient.java`, the error handling is more granular — it logs the response body
because Claude returns detailed error JSON:

```java
// From ClaudeClient.java — complete()
} catch (WebClientResponseException e) {
    // Claude returns error details in the body — log it for fast debugging
    log.error("Claude API error {} — body: {}",
            e.getStatusCode(), e.getResponseBodyAsString());
    return null;  // null signals "no summary available" to SummaryService

} catch (Exception e) {
    log.error("Unexpected error calling Claude: {}", e.getMessage(), e);
    return null;
}
```

### Custom exceptions in VaadVivaad

```java
// scraper/exception/ScraperException.java
public class ScraperException extends RuntimeException {
    public ScraperException(String message) { super(message); }
    public ScraperException(String message, Throwable cause) { super(message, cause); }
}

// scraper/exception/CaptchaException.java
public class CaptchaException extends RuntimeException {
    public CaptchaException(String message) { super(message); }
    public CaptchaException(String message, Throwable cause) { super(message, cause); }
}
```

`CaptchaException` is distinct from `ScraperException` so the caller can decide:
- On `CaptchaException` — retry with a fresh CAPTCHA (solvable problem)
- On `ScraperException` — log and fail (likely a structural or connectivity issue)

This is the **exception hierarchy as communication** pattern: exceptions carry semantic
information about what went wrong AND whether recovery is possible.

---

## 6. Timeout Configuration

### Two different timeouts — and why both matter

```
Client                          Server
  |---TCP handshake (SYN/SYN-ACK/ACK)---|  ← CONNECT TIMEOUT
  |---HTTP request sent---------------->|
  |                                      |  Server processing...
  |<--HTTP response bytes---------------|  ← READ TIMEOUT (starts after connection)
```

- **Connection timeout** — how long to wait for the TCP connection to be established
- **Read timeout** — how long to wait for response bytes after the connection is open
- **Response timeout** (Reactor Netty specific) — total time from request sent to full response received

### VaadVivaad timeout configuration

From `WebClientConfig.java` — the eCourts client:

```java
// From WebClientConfig.java — eCourtWebClient()
HttpClient httpClient = HttpClient.create()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)   // 10s TCP handshake
    .responseTimeout(Duration.ofSeconds(30))                 // 30s for full response
    .doOnConnected(conn -> conn
        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))   // Netty handler
        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));// For request body send
```

The code's own comment explains the why:
```java
// WHY these specific timeouts?
//
// eCourts is a government portal — it is SLOW.
// connectTimeout: 10s — if server doesn't respond to TCP handshake in 10s,
//                       it's likely down. Don't wait forever.
// readTimeout:    30s — page can take 20+ seconds to render on their end.
//                       30s gives buffer without hanging a thread indefinitely.
//
// These are NOT the same thing:
// - Connect timeout = TCP handshake
// - Read timeout = waiting for response bytes after connection established
```

For Claude (a reliable commercial API):
```java
// From WebClientConfig.java — claudeWebClient()
HttpClient httpClient = HttpClient.create()
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
    .responseTimeout(Duration.ofSeconds(claudeTimeoutSeconds)); // From application.yml: 30s
```

Claude doesn't need `ReadTimeoutHandler` added separately because it's a well-behaved API
that sends headers quickly. The `responseTimeout` is sufficient.

### Per-request timeout override

You can also set timeout at the individual request level in the reactive chain:

```java
// From ECourtWebClient.java — fetchSearchPage()
webClient.get()
    .uri("/ecourtindia_v6/?p=casestatus/index&app_token=")
    .retrieve()
    .bodyToMono(String.class)
    .timeout(Duration.ofSeconds(30))  // Per-request timeout — overrides connector timeout
    .block();
```

The `.timeout()` operator in the reactive chain adds a `TimeoutException` if the Mono
doesn't complete within the duration. This is **different** from the Netty-level timeout
configured in `HttpClient` — one is at the reactive operator level, the other at the
TCP/socket level.

---

## 7. Request/Response Logging with ExchangeFilterFunction

### What is ExchangeFilterFunction?

`ExchangeFilterFunction` is middleware for WebClient — it wraps every request/response pair
and lets you inspect, modify, or log them. It is analogous to:
- Express.js middleware (`app.use(...)`)
- `axios` interceptors (`axios.interceptors.request.use(...)`)

### VaadVivaad's logging filters

From `WebClientConfig.java`:

```java
// Request logging filter
private ExchangeFilterFunction logRequest() {
    return ExchangeFilterFunction.ofRequestProcessor(request -> {
        log.debug("Scraper → [{}] {} | Headers: {}",
                request.method(), request.url(), request.headers());
        return Mono.just(request);  // Return the request unchanged — we only observed it
    });
}

// Response logging filter
private ExchangeFilterFunction logResponse() {
    return ExchangeFilterFunction.ofResponseProcessor(response -> {
        log.debug("Scraper ← Status: {} | Headers: {}",
                response.statusCode(), response.headers().asHttpHeaders());
        return Mono.just(response);  // Return unchanged
    });
}
```

Applied to the WebClient builder:
```java
WebClient.builder()
    // ...
    .filter(logRequest())
    .filter(logResponse())
    .build();
```

The code comments explain why we use filters instead of regular logging:

```java
// WHY logging filters here instead of just using application logs?
//
// WebClient requests happen on Netty I/O threads. Standard @Slf4j
// logging in service classes won't capture the actual HTTP-level
// details (status codes, headers). Filter functions run in the
// reactive pipeline and have access to the actual exchange.
//
// For scraping specifically, knowing EXACTLY what was sent and
// received is critical for debugging.
```

### Other common uses for ExchangeFilterFunction

```java
// Auth token injection (great for OAuth2 clients)
ExchangeFilterFunction authFilter = (request, next) -> {
    ClientRequest authenticated = ClientRequest.from(request)
        .header("Authorization", "Bearer " + tokenProvider.getToken())
        .build();
    return next.exchange(authenticated);
};

// Retry filter (alternative to .retryWhen in the chain)
ExchangeFilterFunction retryFilter = (request, next) ->
    next.exchange(request)
        .flatMap(response -> {
            if (response.statusCode().value() == 429) {
                // Rate limited — delay and retry
                return Mono.delay(Duration.ofSeconds(2))
                    .then(next.exchange(request));
            }
            return Mono.just(response);
        });
```

---

## 8. The block() Method — When and Why Not

### What `.block()` does

`.block()` subscribes to a `Mono` and waits synchronously for it to complete, returning
the value. It converts the reactive world back into the imperative world.

```java
// Reactive — non-blocking description
Mono<String> htmlMono = webClient.get().uri("...").retrieve().bodyToMono(String.class);

// .block() subscribes and waits — the current thread parks here until done
String html = htmlMono.block();
// html is now the actual String, not a Mono
```

### Why `.block()` is generally discouraged

In a fully reactive system (Spring WebFlux), **you must never call `.block()`** on a Netty
I/O thread. Netty's event loop is single-threaded per core — if you block that thread
waiting for I/O, you deadlock the entire event loop. Other requests queue up behind it
with no thread to process them.

```
Netty I/O Thread:
  Request A arrives → starts HTTP call → .block() → WAITING...
  Request B arrives → needs the I/O thread → QUEUED...
  Request C arrives → needs the I/O thread → QUEUED...
  ...
  Request A completes → thread unblocks → processes Request B
  But now latency is O(N) instead of O(1) for concurrent requests
```

### Why VaadVivaad uses `.block()` — and why it is acceptable here

VaadVivaad is a **Spring MVC application** (not WebFlux). Requests are handled by Tomcat
on traditional thread-pool threads, not Netty I/O threads. Calling `.block()` on a Tomcat
worker thread is completely safe — you are parking a thread that was already going to be
parked (since MVC is blocking by design).

From `ECourtWebClient.java`:
```java
private String fetchSearchPage() {
    return webClient.get()
            .uri("/ecourtindia_v6/?p=casestatus/index&app_token=")
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .block();  // Called from a Tomcat thread — safe in MVC context
}
```

And from `ClaudeClient.java`:
```java
ClaudeResponse response = claudeWebClient.post()
    .uri(MESSAGES_PATH)
    .bodyValue(request)
    .retrieve()
    .bodyToMono(ClaudeResponse.class)
    .timeout(Duration.ofSeconds(timeoutSeconds))
    .block();  // Same — Tomcat thread context, acceptable
```

### The trade-off

| Approach              | Thread usage                      | Complexity | Good for                             |
|-----------------------|-----------------------------------|------------|--------------------------------------|
| `.block()` in MVC     | 1 Tomcat thread parked per call   | Low        | Most apps, simple integration        |
| Full reactive chain   | 0 threads parked, async callbacks | High       | High-concurrency, streaming scenarios|

VaadVivaad makes the pragmatic choice: use WebClient for its modern API and connection
management features, but `.block()` at the service boundary to stay in the familiar
imperative model. This is an extremely common pattern in real production Spring Boot apps.

### When `.block()` would cause problems

If VaadVivaad were ever migrated to Spring WebFlux (for maximum throughput), every `.block()`
call would need to be eliminated and replaced with reactive chains. The migration path is
intentionally preserved: the reactive operators (`.map()`, `.flatMap()`, `.onErrorMap()`) are
already in the pipeline — only the final `.block()` needs to be removed.

---

## 9. Retry Logic

### The basic retry operators

```java
// Retry up to 3 times on any error
.retry(3)

// Retry with fixed delay
.retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(2)))

// Retry with exponential backoff
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
    .maxBackoff(Duration.ofSeconds(10)))

// Retry only on specific exception types
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
    .filter(e -> e instanceof WebClientResponseException.InternalServerError
             || e instanceof java.util.concurrent.TimeoutException))
```

### When to retry vs when NOT to retry

This is a **senior-level judgment call** that interviewers probe:

| HTTP Status | Retry? | Reason                                                          |
|-------------|--------|-----------------------------------------------------------------|
| 429         | Yes (with delay) | Rate limited — wait and retry                          |
| 500, 502    | Yes    | Transient server error — server may recover                     |
| 503         | Yes    | Service unavailable — often temporary                           |
| 400         | NO     | Bad request — your request is wrong, retrying won't help       |
| 401         | NO     | Unauthorized — wrong credentials, retrying won't help          |
| 403         | NO     | Forbidden — access denied, retrying won't help                 |
| 404         | NO     | Not found — the resource doesn't exist                         |

Retry on 4xx errors wastes resources and can get your IP banned (especially true for
eCourts scraping where rate limiting is already a concern).

### What retry looks like for eCourts (conceptual)

VaadVivaad's current implementation doesn't add explicit retry in the reactive chain —
it could look like this for eCourts:

```java
// Conceptual — how you'd add retry to the eCourts fetch
private String fetchSearchPage() {
    return webClient.get()
            .uri("/ecourtindia_v6/?p=casestatus/index&app_token=")
            .retrieve()
            .onErrorMap(WebClientResponseException.class, e -> {
                if (e.getStatusCode().is4xxClientError()) {
                    // Do NOT retry 4xx — rethrow as non-retryable
                    return new ScraperException("Client error: " + e.getStatusCode(), e);
                }
                return e;  // Let 5xx propagate and be retried
            })
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(30))
            .retryWhen(Retry.backoff(2, Duration.ofSeconds(3))
                .filter(e -> !(e instanceof ScraperException)))  // Don't retry ScraperException
            .block();
}
```

### Exponential backoff — why it matters

If 100 clients all get a 503 and all retry after exactly 2 seconds, they all hit the
server at the same moment — creating another spike. Exponential backoff with **jitter**
(randomness) spreads retries out:

```java
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
    .jitter(0.5)    // ±50% randomness on the delay
    .maxBackoff(Duration.ofSeconds(30)))
// Retry 1: ~1s ±0.5s
// Retry 2: ~2s ±1s
// Retry 3: ~4s ±2s
```

---

## 10. Reactive vs Blocking in Spring Boot — The Hybrid Model

### The three models

**1. Traditional Spring MVC (fully blocking)**
- Tomcat thread pool (default 200 threads)
- `RestTemplate` or `WebClient.block()` for HTTP calls
- Easy to understand, easy to debug, works fine for most apps

**2. Spring WebFlux (fully reactive)**
- Netty event loop (threads = CPU cores)
- All layers must be non-blocking: controllers, services, repositories, HTTP clients
- Requires reactive database drivers (R2DBC for PostgreSQL)
- Maximum throughput, minimum thread usage, highest complexity

**3. Hybrid — Spring MVC + WebClient (VaadVivaad's approach)**
- Tomcat handles requests (blocking)
- WebClient makes outbound HTTP calls (non-blocking at the Netty transport level)
- `.block()` brings the result back to the Tomcat thread
- Benefits: modern WebClient API, connection pooling, filter chain, without full reactive migration

### Why the hybrid makes sense for VaadVivaad

VaadVivaad uses PostgreSQL via JPA (Hibernate). As of today, Hibernate's standard blocking
mode is mature, well-understood, and has excellent tooling. Reactive R2DBC is available but
adds significant complexity. If you're not blocking on DB calls, blocking on HTTP calls is
your bottleneck — so going full WebFlux while keeping blocking JPA would give you WebFlux
complexity with zero non-blocking benefit.

The pragmatic call: blocking MVC + WebClient `.block()` = all the ergonomic benefits of
WebClient (filter chain, JSON codecs, reactive operators for transformation) without the
cognitive overhead of full reactive programming.

---

## 11. Two WebClients in VaadVivaad — Why Separate Beans

### The two beans

From `WebClientConfig.java`:

```java
@Bean(name = "eCourtWebClient")
public WebClient eCourtWebClient() { ... }

@Bean(name = "claudeWebClient")
public WebClient claudeWebClient() { ... }
```

Injected with `@Qualifier`:
```java
// In ECourtWebClient.java
public ECourtWebClient(
        @Qualifier("eCourtWebClient") WebClient webClient,
        CaptchaResolver captchaResolver) { ... }

// In ClaudeClient.java
public ClaudeClient(@Qualifier("claudeWebClient") WebClient claudeWebClient) { ... }
```

### Why not one shared WebClient?

The code comment in `WebClientConfig.java` explains:

```java
// WHY a separate WebClient bean for Claude?
//
// Claude and eCourts have completely different:
// - Base URLs
// - Auth headers (API key vs. session cookies)
// - Timeout requirements (Claude is faster, more reliable)
// - Content types (JSON vs. form-urlencoded)
//
// Sharing one WebClient would mean configuring it with no base URL
// and passing full URLs everywhere — losing the benefit of base URL
// configuration. Separate beans, separate concerns.
```

Detailed comparison:

| Concern           | eCourtWebClient                         | claudeWebClient                           |
|-------------------|-----------------------------------------|-------------------------------------------|
| Base URL          | `https://services.ecourts.gov.in`       | `https://api.anthropic.com`               |
| Auth              | Session cookies + User-Agent spoofing   | `x-api-key` header                        |
| Content-Type      | `application/x-www-form-urlencoded`     | `application/json`                        |
| Connect timeout   | 10s                                     | 10s                                       |
| Read timeout      | 30s (slow government portal)            | 30s (configurable via `application.yml`)  |
| Extra headers     | User-Agent, Referer, Accept-Language    | `anthropic-version: 2023-06-01`           |
| Logging filters   | Yes (request + response)                | No (added separately if needed)           |
| Reliability       | Unreliable (CAPTCHA, rate limits, flaky)| Reliable commercial API                   |

---

## 12. eCourts Scraping Challenges

### The four hard problems

#### 1. Bot detection via User-Agent

eCourts checks the HTTP `User-Agent` header. The default WebClient user agent (`reactor/3.x`)
is immediately identified as a bot and returns empty responses or blocks the request entirely.

The fix — from `WebClientConfig.java`:
```java
.defaultHeader(HttpHeaders.USER_AGENT,
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0.0.0 Safari/537.36")
.defaultHeader(HttpHeaders.REFERER,
    "https://services.ecourts.gov.in/ecourtindia_v6/")
```

The `Referer` header is also checked — eCourts validates that requests appear to originate
from their own portal, not from an external domain.

#### 2. CAPTCHA on every search

eCourts presents a CAPTCHA on the search page. This is intentionally anti-scraping. The
CAPTCHA URL is session-specific — you cannot pre-solve it. The flow must be:

```
1. GET search page → extract CAPTCHA image URL from HTML
2. GET CAPTCHA image bytes
3. Solve CAPTCHA (in dev: NoOpCaptchaResolver returns "" and hopes it works)
4. POST form with CNR + CAPTCHA solution
```

From `ECourtWebClient.java`:
```java
// Step 1: GET the search page
String searchPageHtml = fetchSearchPage();

// Step 2: Extract CAPTCHA image URL from the page
byte[] captchaImageBytes = fetchCaptchaImage(searchPageHtml);
// Uses Jsoup: doc.getElementById("captchaimg").attr("src")

// Step 3: Solve CAPTCHA
String captchaSolution = captchaResolver.solve(captchaImageBytes);

// Step 4: POST the form with CNR + CAPTCHA solution
return postCnrSearchForm(cnrNumber, captchaSolution);
```

The `CaptchaResolver` interface with a `NoOpCaptchaResolver` (dev) vs a real OCR or
third-party CAPTCHA-solving service (prod) follows the Strategy pattern — the scraping
logic doesn't change, only the solving implementation.

#### 3. Rate limiting

eCourts will temporarily block IPs that send too many requests too quickly. The scraperService
implements a freshness threshold of 6 hours:

```java
// From ScraperService.java
private static final int FRESHNESS_HOURS = 6;

private boolean isFresh(CourtCase courtCase) {
    if (courtCase.getLastScrapedAt() == null) return false;
    return courtCase.getLastScrapedAt()
            .isAfter(LocalDateTime.now().minusHours(FRESHNESS_HOURS));
}
```

This means a case will only be re-scraped if it hasn't been updated in 6+ hours, naturally
throttling requests to eCourts. Production would add explicit delay between requests if
multiple cases need scraping in sequence.

#### 4. Unstable HTML structure

eCourts' HTML is generated by a legacy PHP application. The structure changes without
notice. The parser (`ECourtHtmlParser`) uses Jsoup to navigate the DOM by ID and class
attributes, but those can change. `ParsedCaseData` acts as an anti-corruption layer:

```java
// From ParsedCaseData.java
// Anti-corruption layer between eCourts HTML chaos and our clean domain model
public record ParsedCaseData(
        String cnrNumber,
        String caseType,
        String filingNumber,
        LocalDate filingDate,
        // ... all the fields the parser could find
        List<ParsedHearing> hearings
) {
    public record ParsedHearing(
            LocalDate hearingDate,
            String purpose,
            LocalDate nextHearingDate,
            String notes
    ) {}
}
```

Fields can be null if parsing fails for a particular field. `ScraperService` validates
and handles nulls before persisting to the database.

---

## 13. Claude API Integration

### The request structure

Claude's API is a REST JSON API. From the DTOs:

```java
// ai/dto/ClaudeRequest.java
public record ClaudeRequest(
        String model,       // "claude-3-haiku-20240307"
        int max_tokens,     // 300 (matches 2-3 sentence Hindi summary budget)
        List<Message> messages
) {
    public record Message(
            String role,    // "user" or "assistant"
            String content  // The actual prompt text
    ) {}
}
```

Serializes to:
```json
{
  "model": "claude-3-haiku-20240307",
  "max_tokens": 300,
  "messages": [
    { "role": "user", "content": "You are a legal assistant..." }
  ]
}
```

### The response structure

```java
// ai/dto/ClaudeResponse.java
@JsonIgnoreProperties(ignoreUnknown = true)  // Critical — Claude adds fields, we ignore them
public record ClaudeResponse(
        List<ContentBlock> content
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
            String type,   // "text"
            String text    // The actual response text
    ) {}

    // Hides the nested navigation from callers
    public String extractText() {
        if (content == null || content.isEmpty()) return null;
        return content.get(0).text();
    }
}
```

Claude's actual response JSON:
```json
{
  "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "type": "message",
  "role": "assistant",
  "model": "claude-3-haiku-20240307",
  "content": [{ "type": "text", "text": "आपकी अगली सुनवाई..." }],
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 245, "output_tokens": 89 }
}
```

`@JsonIgnoreProperties(ignoreUnknown = true)` means `id`, `type`, `role`, `model`,
`stop_reason`, and `usage` are all silently ignored. Without this annotation, Jackson
throws `UnrecognizedPropertyException` when it encounters any field not in your record.
**Always add this when consuming external APIs you don't control.**

### Authentication — x-api-key, not Bearer

From `WebClientConfig.java`:
```java
// Claude uses x-api-key header for authentication.
// NOT Bearer token — this is a common mistake.
// Sending "Authorization: Bearer {key}" returns 401.
.defaultHeader("x-api-key", claudeApiKey)

// Anthropic requires this on every request
.defaultHeader("anthropic-version", "2023-06-01")
```

### application.yml configuration

```yaml
# From application.yml
claude:
  api-key: ${CLAUDE_API_KEY:dummy-key-for-dev}  # Env var with fallback for dev
  base-url: https://api.anthropic.com
  model: claude-3-haiku-20240307
  max-tokens: 300
  timeout-seconds: 30
```

`${CLAUDE_API_KEY:dummy-key-for-dev}` is Spring's property placeholder with default value.
In dev, you don't need the env var (Claude is currently disabled in SummaryService anyway).
In prod, set `CLAUDE_API_KEY` as an environment variable.

### Currently disabled in VaadVivaad

From `SummaryService.java`:
```java
@Transactional
public void generateAndSave(UUID hearingId) {
    log.info("Claude API not configured — skipping summary for hearing: {}", hearingId);
    return;  // Early return — everything below is commented out

    // The full implementation is preserved in comments for when enabled:
    // 1. Find hearing by ID
    // 2. Skip if summary already exists (idempotency)
    // 3. Build prompt with hearing details
    // 4. Call claudeClient.complete(prompt)
    // 5. Save summary to hearing.aiSummaryHindi
}
```

The early return is deliberate — it keeps the RabbitMQ message consumer wired up and
working (no code changes needed to enable the feature) while not making real API calls
during development.

---

## 14. Connection Pooling with Reactor Netty

### What is connection pooling?

Instead of opening a new TCP connection for every HTTP request (expensive: 3-way handshake,
TLS handshake), connection pooling reuses established connections. Reactor Netty manages
this automatically.

### Reactor Netty's default pool settings

```
Max connections per remote host: 500 (pendingAcquireMaxCount)
Max idle time: 20 seconds
Max life time: 60 seconds
Pending acquire timeout: 45 seconds
```

For most applications, these defaults are fine. For VaadVivaad scraping eCourts, the
pool is effectively limited to 1-2 active connections at a time (because the service
waits for each response before starting the next — `.block()` is synchronous).

### Tuning for high throughput (if you needed it)

```java
// Example: custom connection pool for high-throughput use case
ConnectionProvider connectionProvider = ConnectionProvider.builder("custom-pool")
    .maxConnections(100)
    .maxIdleTime(Duration.ofSeconds(30))
    .maxLifeTime(Duration.ofMinutes(5))
    .pendingAcquireTimeout(Duration.ofSeconds(45))
    .evictInBackground(Duration.ofSeconds(120))
    .build();

HttpClient httpClient = HttpClient.create(connectionProvider)
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
    .responseTimeout(Duration.ofSeconds(30));
```

### Why connection pooling matters for external API calls

Without pooling, calling Claude 100 times means 100 TLS handshakes (TLS 1.3 handshake
is ~1 round trip but still adds latency). With pooling, the first call does the handshake,
subsequent 99 calls reuse the connection. For Claude (HTTPS to `api.anthropic.com`), this
is a real win when generating summaries for multiple hearings in rapid succession.

---

## 15. Comparing to Node.js

### The mental model mapping

You know Node's async model deeply. Here's the complete mapping:

| Node.js concept                 | Spring/Reactor equivalent               | Notes                                        |
|---------------------------------|-----------------------------------------|----------------------------------------------|
| `Promise<T>`                    | `Mono<T>`                               | Both represent a future single value         |
| `AsyncIterable` / `Observable`  | `Flux<T>`                               | Stream of 0..N values                        |
| `await promise`                 | `.block()`                              | Both make async sync (Mono/Promise)          |
| `.then(fn)`                     | `.map(fn)` or `.flatMap(fn)`            | `.then()` always flatMaps in JS              |
| `.catch(fn)`                    | `.onErrorResume(fn)`                    | Recovery from error                          |
| `.finally(fn)`                  | `.doFinally(fn)`                        | Always runs                                  |
| `Promise.all([p1, p2])`         | `Mono.zip(m1, m2)` or `Flux.merge()`   | Parallel execution                           |
| `Promise.race([p1, p2])`        | `Mono.firstWithValue(m1, m2)`           | First to complete wins                       |
| `axios.get(url)`                | `webClient.get().uri(url).retrieve()...`| HTTP GET                                     |
| `axios.interceptors.request`    | `ExchangeFilterFunction`                | Request/response middleware                  |
| `axios.defaults.baseURL`        | `.baseUrl()` on builder                 | Default URL prefix                           |
| `axios.defaults.headers`        | `.defaultHeader()` on builder           | Default headers                              |
| `p.then(a).then(b).catch(c)`    | `.map(a).map(b).onErrorResume(c)`       | Pipeline chaining                            |

### Side-by-side: making a POST request

**Node.js with axios:**
```javascript
const response = await axios.post('https://api.anthropic.com/v1/messages', {
    model: 'claude-3-haiku-20240307',
    max_tokens: 300,
    messages: [{ role: 'user', content: prompt }]
}, {
    headers: {
        'x-api-key': process.env.CLAUDE_API_KEY,
        'anthropic-version': '2023-06-01',
        'Content-Type': 'application/json'
    },
    timeout: 30000
});
const text = response.data.content[0].text;
```

**Java with WebClient (VaadVivaad style):**
```java
// From ClaudeClient.java — simplified
ClaudeRequest request = new ClaudeRequest(
    model, maxTokens, List.of(new ClaudeRequest.Message("user", prompt))
);

ClaudeResponse response = claudeWebClient.post()
    .uri(MESSAGES_PATH)
    .bodyValue(request)                          // Jackson serializes to JSON
    .retrieve()
    .bodyToMono(ClaudeResponse.class)            // Jackson deserializes from JSON
    .timeout(Duration.ofSeconds(timeoutSeconds))
    .block();                                    // Like: await in MVC context

String text = response.extractText();           // content[0].text()
```

The structure is identical — the Java version just has explicit types everywhere and uses
`.block()` where JS uses `await`.

### Parallel requests — Promise.all vs Mono.zip

**Node.js:**
```javascript
const [caseData, judgeInfo] = await Promise.all([
    axios.get(`/api/cases/${cnr}`),
    axios.get(`/api/judges/${judgeId}`)
]);
```

**Java with Reactor (if VaadVivaad used it):**
```java
Mono<CaseData> caseMono = webClient.get().uri("/api/cases/{cnr}", cnr)
    .retrieve().bodyToMono(CaseData.class);

Mono<JudgeInfo> judgeMono = webClient.get().uri("/api/judges/{id}", judgeId)
    .retrieve().bodyToMono(JudgeInfo.class);

// Execute in parallel, combine results
Mono<Pair<CaseData, JudgeInfo>> combined = Mono.zip(caseMono, judgeMono)
    .map(tuple -> Pair.of(tuple.getT1(), tuple.getT2()));

// Or with block() in MVC context:
var tuple = Mono.zip(caseMono, judgeMono).block();
CaseData caseData = tuple.getT1();
JudgeInfo judgeInfo = tuple.getT2();
```

### The event loop analogy

Node.js is inherently single-threaded with an event loop. Java + Spring MVC is multi-threaded
with thread-per-request. Java + Spring WebFlux uses Netty's event loop, which is conceptually
identical to Node's event loop — small number of threads, many concurrent connections, no
blocking I/O on the event loop threads.

VaadVivaad sits between those two Java models — it gets the benefit of non-blocking I/O at
the Netty transport level (many concurrent outbound connections) while the application layer
remains familiar blocking MVC code.

---

## 16. Senior Interview Q&A

**Q1: Why did Spring deprecate RestTemplate and introduce WebClient?**

RestTemplate is synchronous and blocking — one thread per HTTP call. Under high concurrency,
thread pools become bottlenecks and you need large JVM heaps for stack space. WebClient
uses Reactor Netty's event loop, allowing a small number of threads to handle many concurrent
connections. Additionally, WebClient has a composable, functional API that supports
transformation pipelines, error handling operators, retry logic, and filter chains natively.
RestTemplate's API is imperative and harder to compose.

---

**Q2: What is the difference between `.retrieve()` and `.exchangeToMono()`? When would you use each?**

`.retrieve()` is the safe default. It automatically manages the response lifecycle — consuming
the response body and returning the connection to the pool. It throws `WebClientResponseException`
on non-2xx status codes, which you handle with `.onErrorMap()`.

`.exchangeToMono()` gives you access to the full `ClientResponse` before you decide how to
consume the body. You'd use it when you need to inspect response headers or status before
deciding how to deserialize the body. The critical risk: if you use `.exchangeToMono()` and
fail to consume the response body in every code path, you leak connections from the Netty pool.
Under load, the pool drains and all new requests hang. This is a hard-to-find production bug.
Rule: default to `.retrieve()`, reach for `.exchangeToMono()` only with intentional justification.

---

**Q3: In VaadVivaad, WebClient calls `.block()`. Is this acceptable?**

Yes, in this context. VaadVivaad is a Spring MVC application — requests arrive on Tomcat
thread-pool threads. Calling `.block()` parks a Tomcat worker thread, which is exactly what
blocking I/O (like JDBC) already does. The application was never non-blocking to begin with.

The antipattern is calling `.block()` on a **Netty event-loop thread** in a WebFlux
application — that would deadlock the event loop and block all requests on that thread.
To distinguish the two, understand where the calling code runs: Tomcat worker = safe,
Netty I/O thread = deadlock.

---

**Q4: What is an ExchangeFilterFunction? How does VaadVivaad use it?**

`ExchangeFilterFunction` is WebClient's middleware — it wraps every request/response pair.
VaadVivaad uses it in `WebClientConfig` for request/response logging. The filter receives
the request before it's sent, can inspect or modify it, and returns it for the next filter
or for actual dispatch. Similarly for responses.

This is the right place for cross-cutting concerns: logging, auth-token injection, metrics
collection, rate limiting, and retry logic. Putting this in the builder means every request
made through that WebClient instance automatically gets the behavior — no boilerplate at each
call site.

---

**Q5: Why does VaadVivaad have two separate WebClient beans instead of one?**

eCourts and Claude have fundamentally different requirements:
- Different base URLs (no shared baseUrl possible)
- Different auth (browser-spoofing headers vs x-api-key)
- Different content types (form-urlencoded vs JSON)
- Different timeout profiles (both 30s, but different Netty handler configurations)
- Different reliability profiles (Claude is stable, eCourts is flaky)

One shared WebClient would require no `baseUrl()` (losing a key ergonomic feature) and would
mix auth headers from both targets on every request (a security issue). Separate beans follow
the Single Responsibility Principle — each WebClient knows exactly one external system.

---

**Q6: What is the difference between `Mono.map()` and `Mono.flatMap()`?**

`.map()` applies a synchronous function that returns a plain value. `.flatMap()` applies
a function that returns another `Mono` (or `Flux`) and flattens it — avoiding `Mono<Mono<T>>`.

```java
// .map() — synchronous transform
Mono<ParsedCaseData> parsed = htmlMono.map(html -> parser.parse(html));
// parse() returns ParsedCaseData (not a Mono)

// .flatMap() — async transform
Mono<CourtCase> saved = parsed.flatMap(data -> repository.save(data));
// if save() returned Mono<CourtCase>, flatMap prevents Mono<Mono<CourtCase>>
```

In Node.js: `.then()` always flatMaps — returning a Promise from a `.then()` callback
unwraps it automatically. Java requires you to be explicit: `.map()` for sync, `.flatMap()`
for async. This explicitness is intentional — it prevents accidentally wrapping values in
unnecessary Mono layers.

---

**Q7: How do you configure exponential backoff retry for HTTP calls?**

```java
.retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
    .maxBackoff(Duration.ofSeconds(30))
    .jitter(0.5)
    .filter(e -> e instanceof WebClientResponseException.InternalServerError
             || e instanceof java.util.concurrent.TimeoutException))
```

The `filter()` is critical — you only retry on transient errors (5xx, timeout). You must
NOT retry on 4xx client errors: a bad request will still be bad on the next attempt, and
retrying 401s wastes resources. For eCourts specifically, retrying on 4xx could trigger
IP-based rate limiting or banning.

---

**Q8: What is `@JsonIgnoreProperties(ignoreUnknown = true)` and why is it essential for external API clients?**

When Jackson deserializes JSON into a Java class/record, by default it throws
`UnrecognizedPropertyException` if the JSON contains a field not mapped in your class.
External APIs (Claude, eCourts, any third-party) add new response fields without warning.
Without `ignoreUnknown = true`, a Claude API update that adds a new field (like a new
`system_fingerprint` field) would cause your application to throw exceptions on every
Claude response.

`@JsonIgnoreProperties(ignoreUnknown = true)` tells Jackson to silently ignore unknown fields.
VaadVivaad's `ClaudeResponse` and `ClaudeResponse.ContentBlock` both carry this annotation —
you only map `content[0].text`, and all other Claude response fields are safely ignored.

---

**Q9: What is the connection timeout vs read timeout vs response timeout distinction in WebClient?**

- **Connection timeout** (`ChannelOption.CONNECT_TIMEOUT_MILLIS`): TCP-level — how long to
  wait for the server to complete the 3-way handshake. If eCourts' server is down or
  unreachable, this fires first.

- **Read timeout** (`ReadTimeoutHandler`): how long to wait for data after the connection
  is established but before the full response arrives. Catches cases where the server
  accepted the connection but stopped sending data mid-response.

- **Response timeout** (`.responseTimeout()` on `HttpClient`): total time from when the
  request is sent to when the full response body is received. The broadest timeout.

- **Reactive `.timeout()`** on the Mono: operator-level — how long the reactive chain waits
  before emitting a `TimeoutException`. This is orthogonal to the Netty-level timeouts — it
  can fire even if Netty would have waited longer.

VaadVivaad uses all four for the eCourts client because eCourts is unreliable — you want
defense in depth against hangs at any layer.

---

**Q10: VaadVivaad currently has SummaryService returning early (Claude disabled). How would you safely enable it in production?**

Three steps:

1. **Set the environment variable**: `CLAUDE_API_KEY=<real-key>` in the deployment environment.
   The application.yml already handles this with `${CLAUDE_API_KEY:dummy-key-for-dev}`.

2. **Remove the early return** in `SummaryService.generateAndSave()` and uncomment the
   implementation. The implementation is already complete in comments.

3. **Add circuit breaking** — Claude is a paid API. If it's down or rate-limited, you don't
   want every summary request to hammer it. Add Resilience4j circuit breaker around
   `claudeClient.complete(prompt)`: after N consecutive failures, open the circuit and
   skip Claude calls for a timeout period. Log a metric so you know the circuit is open.

The RabbitMQ consumer and the full prompt-building logic are already in place — enabling
Claude is a 3-line code change plus the env var. The architecture was designed for this.

---

## 17. Senior Differentiators

These are the concepts that separate senior engineers from mid-level engineers in WebClient discussions:

### 1. Connection leak awareness
You can articulate WHY `.retrieve()` is safer than `.exchangeToMono()`, what "consuming the
response body" means at the TCP level, and what symptoms a connection pool leak produces
(requests hanging, apparent deadlocks under load with no exception).

### 2. Thread context awareness
You know exactly which thread calls `.block()` in VaadVivaad (Tomcat worker), why that's
safe, and under which circumstances it would NOT be safe (Netty I/O thread in WebFlux app).
You don't just say "don't use block()" — you say "don't use block() on a Netty I/O thread."

### 3. Retry strategy nuance
You know the difference between retryable and non-retryable errors (4xx vs 5xx), you
understand that exponential backoff with jitter prevents thundering herd, and you know
that blindly retrying on 4xx wastes resources and can get your IP banned.

### 4. Separate WebClient beans is an architectural decision
You can articulate the Single Responsibility Principle as it applies to HTTP clients:
one client per external system, because each system has different auth, different error
handling, different timeout profiles, and different reliability characteristics. Mixing
them creates hidden coupling.

### 5. @JsonIgnoreProperties is defensive design
You treat external API responses as contracts that can change without notice. Every response
DTO for an external API gets `@JsonIgnoreProperties(ignoreUnknown = true)`. This is
production hygiene, not optional.

### 6. The hybrid model is a legitimate architectural choice
You can defend VaadVivaad's "blocking MVC + WebClient.block()" approach: it gets connection
pooling, filter chains, modern reactive operators for transformation, and the ergonomic
builder API — without the cognitive overhead of full reactive programming or the need to
migrate Hibernate to R2DBC. The architecture is not a compromise; it's a deliberate trade-off
that buys simplicity at the cost of maximum throughput (which the application doesn't need).

### 7. You know what full reactive actually requires
When an interviewer asks "why not use full WebFlux?", you can explain that full reactive
means: all layers non-blocking (controller, service, repository), reactive database drivers
(R2DBC for PostgreSQL, Redis Reactive for caching), reactive message consumers, no `.block()`
anywhere. Each of these adds complexity. The payoff is maximum throughput with minimum
threads — worth it for services that need to handle 10k+ concurrent connections, not worth
it for services where the bottleneck is a slow government portal that enforces rate limits.

### 8. ExchangeFilterFunction is the right place for cross-cutting concerns
You reach for `ExchangeFilterFunction` instead of wrapping every call site in logging boilerplate.
You can explain why it must live on the WebClient builder (not in the calling service), and
you can sketch out how you'd implement an auth-token-refresh filter for OAuth2 scenarios
(inspect response for 401, refresh token, retry original request — all in the filter, invisible
to callers).

---

*This file is part of the VaadVivaad interview prep series. Cross-reference:*
- *`05_spring_core_ioc.md` — for `@Bean`, `@Qualifier`, `@Configuration` concepts*
- *`06_spring_data_jpa.md` — for why VaadVivaad uses blocking JPA instead of R2DBC*
- *`09_rabbitmq_async.md` — for how SummaryService receives its work via message queue*
