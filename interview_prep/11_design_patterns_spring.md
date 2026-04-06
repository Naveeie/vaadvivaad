# 11. Design Patterns in Spring Boot — VaadVivaad Deep Dive

> **Target audience:** Developer with strong Node.js/React background, learning Java/Spring Boot.
> **Goal:** Understand the *why* behind patterns — not just the names. Interviewers want reasoning, not recitation.

---

## Why Design Patterns Are a Senior Signal

When an interviewer asks "how does `@Transactional` work?" they are not asking you to read the annotation docs.
They are checking whether you know it's a **Proxy Pattern** implementation — that Spring creates a CGLIB subclass of your bean, wraps the method call, opens a transaction before calling your code, and commits/rolls back after. If you say "it wraps the method in a transaction," you're a mid-level engineer. If you say "Spring creates a dynamic proxy — CGLIB for classes, JDK proxy for interfaces — and the proxy intercepts the method call, delegates to the transaction interceptor, and your actual method runs inside that managed context," you're senior.

Frameworks like Spring are not magic. They are patterns implemented in Java. When you know the patterns, you can:

- Debug unexpected behavior (self-invocation bypasses `@Transactional` — now you know why)
- Answer "how does X work internally" questions with confidence
- Design your own systems using the same logic
- Recognize when Spring is protecting you and when it isn't

The patterns below are all present in VaadVivaad. We will look at the actual source code in each case.

---

## 1. Singleton Pattern

### The Concept

One instance. Shared everywhere. No re-creation.

### In Spring

Every Spring bean is a singleton by default — one instance per `ApplicationContext`. When `ScraperService`, `SubscriptionService`, and `JwtAuthFilter` are all injected as dependencies, Spring does not create new objects each time. It returns the same instance from its internal registry.

```java
// VaadVivaadApplication.java
@SpringBootApplication
@EnableScheduling
@EnableCaching
public class VaadVivaadApplication {
    public static void main(String[] args) {
        SpringApplication.run(VaadVivaadApplication.class, args);
    }
}
```

`SpringApplication.run()` creates the `ApplicationContext`. That context holds **one** instance of each `@Service`, `@Component`, `@Repository`, and `@Configuration` bean. That's the container-managed singleton.

### Thread Safety Implication — This Is What Separates Juniors From Seniors

A singleton is shared across all threads. In a web server handling 100 concurrent requests, all 100 use the **same** `ScraperService` instance simultaneously.

**Safe:** Stateless beans. `ScraperService` has no mutable instance fields — its fields (`courtCaseRepository`, `rabbitTemplate` etc.) are also singletons injected once at construction. Reading them concurrently is safe.

**Dangerous:** Stateful instance variables in a singleton.

```java
// WRONG — never do this in a Spring service
@Service
public class BadService {
    private String currentUser; // shared mutable state — race condition under load

    public void doWork(String user) {
        this.currentUser = user; // Thread A sets "alice"
        // Thread B sets "bob"
        // Thread A reads "bob" — wrong user!
        process(this.currentUser);
    }
}
```

The rule: **beans should be stateless**. Request-scoped state lives in local variables (stack, not heap), which are thread-local by nature.

### Node.js Analogy

Node modules are singletons via the module cache. When you `require('./userService')` from two different files, Node returns the same exported object both times. This is the same concept — one instance, shared everywhere. The difference is Node is single-threaded, so race conditions don't apply; Java's multi-threaded model makes this matter much more.

---

## 2. Factory Pattern

### The Concept

Instead of calling `new MyObject()` directly, you ask a factory to create it for you. The factory knows the configuration, the dependencies, the setup steps. You just get back a ready-to-use object.

### In Spring — `@Bean` Methods Are Factories

```java
// config/WebClientConfig.java
@Configuration
public class WebClientConfig {

    @Bean(name = "eCourtWebClient")
    public WebClient eCourtWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl("https://services.ecourts.gov.in")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 ...")
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }
}
```

`eCourtWebClient()` is a factory method. It:
1. Creates an `HttpClient` with specific timeouts
2. Configures the `WebClient` with a base URL, headers, and filter chain
3. Returns a fully configured, ready-to-use `WebClient`

The caller (say, `ECourtWebClient`) does not do any of this setup. It just receives an injectable `WebClient`.

Notice two separate beans — `eCourtWebClient` and `claudeWebClient`. They need completely different configurations (different base URLs, different auth headers, different timeouts). Two factory methods, two different products.

### Abstract Factory — `CacheManager` in `RedisConfig`

```java
// config/RedisConfig.java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration config = RedisCacheConfiguration
            .defaultCacheConfig()
            .entryTtl(Duration.ofHours(1))
            .serializeKeysWith(...)
            .serializeValuesWith(...)
            .disableCachingNullValues();

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
}
```

`CacheManager` is an Abstract Factory. It doesn't just create one thing — it creates individual `Cache` instances for each cache name (`"cases"`, `"users"`, etc.). When `@Cacheable(cacheNames = "cases")` runs, Spring asks the `CacheManager` for a `Cache` named "cases." The `CacheManager` (the abstract factory) produces it with all the configured serialization and TTL settings.

### Spring ApplicationContext = Giant Factory

The entire Spring IoC container is a factory. When you call `SpringApplication.run()`, Spring scans components, resolves dependencies, and constructs all beans in the right order. You never call `new SubscriptionService(...)` yourself. The factory does it, with all the right dependencies injected.

### Node.js Analogy

In Express, you might have a `createRedisClient()` function that configures host, port, password, and retry strategy — then returns the client. That function is a factory. In Spring, `@Bean` methods are that same pattern, but managed by the framework.

---

## 3. Proxy Pattern — The Most Important Spring Pattern

### The Concept

A proxy sits between the caller and the real object. It looks identical to the real object (same interface or subclass). The caller doesn't know it's talking to a proxy. The proxy intercepts the call, does extra work (begin transaction, check cache, check authorization), then delegates to the real object.

```
Client Code
    |
    v
[PROXY — Spring-generated wrapper]
    |  - Opens transaction (@Transactional)
    |  - Checks cache (@Cacheable)
    |  - Checks authorization (@PreAuthorize)
    |
    v
[Your Real Bean — ScraperService, SubscriptionService, etc.]
```

### In VaadVivaad — `@Transactional` on `SubscriptionService`

```java
// subscription/service/SubscriptionService.java
@Service
public class SubscriptionService {

    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequest request) {
        // ... save subscription, publish RabbitMQ event
    }
}
```

You wrote `SubscriptionService`. Spring generates a second class at runtime — something like `SubscriptionService$$SpringCGLIB$$0`. That generated class:

1. Is a subclass of `SubscriptionService` (CGLIB creates it by subclassing your class)
2. Overrides `subscribe()` to open a transaction, call `super.subscribe()`, then commit or rollback

Every other bean that injects `SubscriptionService` actually receives the CGLIB proxy, not your class. They never know.

### JDK Proxy vs CGLIB Proxy

| Mechanism | When Used | How |
|-----------|-----------|-----|
| JDK Dynamic Proxy | Bean implements an interface | Proxy implements the same interface |
| CGLIB Proxy | Bean is a concrete class (no interface) | Proxy subclasses your class |

`SubscriptionService` has no interface — Spring uses CGLIB. If it implemented `ISubscriptionService`, Spring would use JDK proxy. In practice, since Spring Boot 2.x, **CGLIB is the default** even when an interface is present (configurable).

### The Self-Invocation Trap — The Gotcha That Trips Everyone

```java
@Service
public class ScraperService {

    @Transactional
    @CacheEvict(cacheNames = "cases", allEntries = true)
    public CourtCase scrapeOrRefresh(String cnrNumber) {
        // ...
        return upsertCase(parsedData, existing); // calls private method directly
    }

    // Imagine if scrapeOrRefresh called another @Transactional method internally:
    public CourtCase somePublicMethod(String cnr) {
        return scrapeOrRefresh(cnr); // BYPASSES the proxy!
    }
}
```

When `somePublicMethod()` calls `scrapeOrRefresh()` directly, it is calling `this.scrapeOrRefresh()`. `this` refers to the real `ScraperService` object — **not the proxy**. The proxy is never involved. So `@Transactional` on `scrapeOrRefresh()` does nothing when called from within the same class.

This is the single most common senior interview question about Spring proxies. The fix: inject self, or extract to a separate bean, or use `AopContext.currentProxy()`.

### Node.js Analogy

JavaScript `Proxy` objects work the same way at the language level. You wrap an object, intercept `get`/`set`/`apply` traps. Express middleware is the application-level equivalent — each middleware function intercepts the request, does something, and passes control forward.

---

## 4. AOP — Aspect-Oriented Programming

### The Concept

Some concerns cut across your entire application — logging, transaction management, caching, security. AOP lets you extract these into separate modules (Aspects) so your business logic stays clean.

Instead of:
```java
public CourtCase scrapeOrRefresh(String cnrNumber) {
    log.info("Starting scrapeOrRefresh for {}", cnrNumber);
    long start = System.currentTimeMillis();
    // ... begin transaction ...
    try {
        // ... actual business logic ...
        // ... commit transaction ...
    } catch (Exception e) {
        // ... rollback transaction ...
        throw e;
    }
    log.info("scrapeOrRefresh took {}ms", System.currentTimeMillis() - start);
}
```

You write:
```java
@Transactional       // transaction aspect handles begin/commit/rollback
public CourtCase scrapeOrRefresh(String cnrNumber) {
    // ONLY business logic here
}
```

### AOP Vocabulary — Learn These Exactly

| Term | Plain English | VaadVivaad Example |
|------|--------------|-------------------|
| **Aspect** | The class containing cross-cutting logic | `TransactionInterceptor` (Spring's internal), your custom `LoggingAspect` |
| **Advice** | When the aspect runs | `@Before`, `@After`, `@Around`, `@AfterReturning`, `@AfterThrowing` |
| **Pointcut** | Expression defining which methods to intercept | `execution(* com.vaadvivaad.*.service.*.*(..))` |
| **Join Point** | The actual method being intercepted | `SubscriptionService.subscribe()` call |
| **Weaving** | Applying the aspect to the target | Spring does this at runtime via proxy generation |

### Custom Logging Aspect for VaadVivaad

This does not exist in the codebase yet, but this is exactly what a senior would add:

```java
// (hypothetical) common/aspect/ServiceLoggingAspect.java
@Aspect
@Component
public class ServiceLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ServiceLoggingAspect.class);

    // Pointcut: any method in any class ending with "Service" in VaadVivaad packages
    @Pointcut("execution(* com.vaadvivaad..service.*.*(..))")
    public void serviceLayer() {}

    // Around advice: runs before AND after the method
    @Around("serviceLayer()")
    public Object logServiceCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().toShortString();
        long start = System.currentTimeMillis();

        log.info(">> {} called", method);
        try {
            Object result = joinPoint.proceed(); // call the actual method
            log.info("<< {} completed in {}ms", method, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("<< {} failed after {}ms: {}", method,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
```

This aspect would automatically log every service call in the entire application without touching a single service class. Add it once, it applies everywhere. That's the power of AOP.

### How `@Transactional` IS AOP

`@Transactional` is not magic. Spring has a `TransactionInterceptor` that is an Around advice. Its pointcut targets methods annotated with `@Transactional`. When you add `@Transactional` to `SubscriptionService.subscribe()`, you are registering that method as a join point for the transaction aspect.

`@Cacheable` similarly triggers `CacheInterceptor`. `@PreAuthorize` triggers `AuthorizationInterceptor`. All annotations are just pointcut markers.

---

## 5. Template Method Pattern

### The Concept

Define the skeleton of an algorithm in a base class. Leave specific steps to subclasses to implement. The base class controls the sequence; subclasses provide the content.

### In VaadVivaad — `JwtAuthFilter` extends `OncePerRequestFilter`

```java
// auth/security/JwtAuthFilter.java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Your logic: extract JWT, validate, set SecurityContext
        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        // ... validate token, set authentication ...
        filterChain.doFilter(request, response);
    }
}
```

The template is `OncePerRequestFilter`. It provides a `doFilter()` method that:
1. Checks whether this filter has already run for this request (the "once per request" guarantee)
2. If not, calls your `doFilterInternal()` (the abstract step you implement)
3. Marks the filter as run

You never implement `doFilter()`. You only implement `doFilterInternal()`. The base class controls the algorithm; you provide the domain-specific step. This is Template Method.

### Another Example — `JpaRepository` Default Implementations

When you write:
```java
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> {
    Optional<CourtCase> findByCnrNumber(String cnrNumber);
}
```

`JpaRepository` has template implementations of `save()`, `findById()`, `findAll()`, etc. implemented in `SimpleJpaRepository`. The template handles entity manager operations, flush, detach. Spring Data generates your custom `findByCnrNumber()` by parsing the method name. The framework owns the algorithm; you declare the variation points (custom query methods).

### Node.js Analogy

Express middleware hierarchy is similar. A base router `app.use()` processes every request; you define the specific handler. NestJS guards use the same pattern — `CanActivate` interface, where the framework calls `canActivate()` and you implement the check logic.

---

## 6. Strategy Pattern

### The Concept

Define a family of algorithms, encapsulate each, and make them interchangeable. The context object depends on an abstraction (interface), not a concrete implementation. Swap the strategy without changing the context.

### In VaadVivaad — `CaptchaResolver` Interface

```java
// scraper/client/CaptchaResolver.java
public interface CaptchaResolver {
    String solve(byte[] captchaImageBytes);
}
```

```java
// scraper/client/NoOpCaptchaResolver.java
@Component
@Profile("dev")
public class NoOpCaptchaResolver implements CaptchaResolver {

    @Override
    public String solve(byte[] captchaImageBytes) {
        log.warn("NoOpCaptchaResolver called — CAPTCHA solving not available in dev profile.");
        return "DEV_SKIP";
    }
}
```

`ScraperService` depends on `CaptchaResolver` (the strategy interface), not on `NoOpCaptchaResolver` (the concrete strategy). In production, a different `@Bean` implements `CaptchaResolver` — perhaps a `TwoCaptchaResolver` that calls the 2Captcha API. `ScraperService` does not change. Zero modification to business logic.

This is why the comment inside `CaptchaResolver.java` says exactly this — it's Open/Closed Principle enabled by Strategy Pattern. Adding a new CAPTCHA provider is a new class, not a modification to existing classes.

**The alternatives without Strategy Pattern would be:**
```java
// BAD — what ScraperService would look like without Strategy Pattern
public String solveCaptcha(byte[] imageBytes) {
    String env = System.getProperty("spring.profiles.active");
    if ("dev".equals(env)) {
        return "DEV_SKIP";
    } else if ("prod-2captcha".equals(env)) {
        return twoCaptchaClient.solve(imageBytes);
    } else if ("prod-anticaptcha".equals(env)) {
        return antiCaptchaClient.solve(imageBytes);
    }
    throw new IllegalStateException("No captcha resolver for profile: " + env);
}
```

That is a maintenance nightmare. Every new captcha service requires modifying `ScraperService`, risking regression in unrelated code.

### Node.js Analogy

Passing different functions to `Array.sort()` is Strategy Pattern. The sort algorithm (context) doesn't change; the comparison function (strategy) does. In Node.js Express apps, different authentication middleware (passport-local, passport-jwt, passport-google) are strategies — same interface, different implementations.

---

## 7. Observer Pattern

### The Concept

An object (publisher/subject) maintains a list of dependents (observers). When state changes, the publisher notifies all observers. Publisher and observers are decoupled — publisher doesn't know who listens; observers register interest independently.

### In VaadVivaad — Spring Events via RabbitMQ

```java
// subscription/service/SubscriptionService.java
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    // ... save subscription to DB ...
    Subscription saved = subscriptionRepository.save(subscription);

    // PUBLISH — SubscriptionService does not know who handles this
    SubscriptionCreatedEvent event = new SubscriptionCreatedEvent(
            saved.getId(),
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            courtCase.getId(),
            courtCase.getCnrNumber(),
            caseDisplay,
            LocalDateTime.now()
    );

    rabbitTemplate.convertAndSend(
            RabbitMQConfig.EXCHANGE,
            RabbitMQConfig.ROUTING_KEY,
            event
    );

    return toResponse(saved, courtCase);
}
```

```java
// notification/consumer/NotificationConsumer.java
@Component
public class NotificationConsumer {

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
        log.info("User: {} subscribed to case: {}", event.userFullName(), event.cnrNumber());
        // send WhatsApp/SMS confirmation (future implementation)
    }
}
```

`SubscriptionService` (publisher) has no import of `NotificationConsumer`. It does not call `notificationConsumer.handleSubscriptionCreated(event)`. It publishes to an exchange. RabbitMQ routes to the queue. `NotificationConsumer` is subscribed to that queue. Complete decoupling.

The `SubscriptionCreatedEvent` record is the message contract:
```java
// subscription/event/SubscriptionCreatedEvent.java
public record SubscriptionCreatedEvent(
        UUID subscriptionId,
        UUID userId,
        String userEmail,
        String userFullName,
        UUID caseId,
        String cnrNumber,
        String caseTitle,
        LocalDateTime subscribedAt
) {}
```

Note: events carry identity and enough context for the consumer to act without a DB call (email for notifications, CNR number for logging). They do NOT carry the full entity object — that would couple the event format to the entity schema.

### In-Process Spring Events vs RabbitMQ Events

Spring also has in-process `ApplicationEventPublisher` / `@EventListener`. The difference:

| Mechanism | Scope | Delivery | Persistence |
|-----------|-------|----------|-------------|
| `ApplicationEventPublisher` | Same JVM | Synchronous (by default) | None — lost on crash |
| RabbitMQ via `RabbitTemplate` | Cross-process | Async | Queue persists messages |

VaadVivaad uses RabbitMQ because: notifications and summary generation should not block the HTTP response, and messages must survive a restart.

### Node.js Analogy

`EventEmitter.on('subscriptionCreated', handler)` / `emitter.emit('subscriptionCreated', event)` is the in-process equivalent. For cross-process: publishing to an AWS SNS topic and subscribing a Lambda is the same Observer pattern at cloud scale.

---

## 8. Decorator Pattern

### The Concept

Wrap an object with another object that adds behavior, while preserving the same interface. You can stack wrappers — each adds a layer of behavior, and they chain together.

### In VaadVivaad — WebClient Filter Chain

```java
// config/WebClientConfig.java
return WebClient.builder()
        .baseUrl("https://services.ecourts.gov.in")
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 ...")
        .filter(logRequest())    // Decorator 1: logs the request
        .filter(logResponse())   // Decorator 2: logs the response
        .build();
```

Each `.filter()` wraps the next one. The execution order:

```
Your code calls webClient.get()
    |
    v
logRequest filter runs (logs outgoing request details)
    |
    v
logResponse filter runs (intercepts and logs the response)
    |
    v
Actual HTTP call to eCourts
    |
    v
Response comes back through the chain in reverse
```

Each `ExchangeFilterFunction` receives the request, does something, and then calls the next function in the chain. This is precisely the Decorator pattern — each filter decorates the underlying HTTP exchange with additional behavior.

### Java Streams Are Also Decorator Pattern

```java
return subscriptionRepository.findByUserId(user.getId())
        .stream()
        .map(sub -> toResponse(sub, sub.getCourtCase()))
        .toList();
```

Each `.map()`, `.filter()` call wraps the previous stream with a new stream that adds a transformation step. The streams are not evaluated until a terminal operation (`.toList()`, `.collect()`, `.count()`) is called. This lazy evaluation chain is the Decorator pattern applied to data pipelines.

### Node.js Analogy

Express middleware `app.use(helmet())`, `app.use(cors())`, `app.use(morgan())` stacks decorators on the request/response cycle. Each middleware wraps the next via `next()`. The pattern is identical.

---

## 9. Builder Pattern

### The Concept

Construct a complex object step by step using a fluent API. Instead of a constructor with 15 parameters (which is unreadable and error-prone), you chain method calls that each set one attribute. Build the final object when ready.

### In VaadVivaad — Everywhere

**RabbitMQ Queue construction:**
```java
// config/RabbitMQConfig.java
@Bean
public Queue notificationQueue() {
    return QueueBuilder.durable(NOTIFICATION_QUEUE)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
            .build();
}
```

**WebClient construction:**
```java
WebClient.builder()
        .baseUrl("https://services.ecourts.gov.in")
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 ...")
        .filter(logRequest())
        .build();
```

**Redis cache configuration:**
```java
// config/RedisConfig.java
RedisCacheConfiguration config = RedisCacheConfiguration
        .defaultCacheConfig()
        .entryTtl(Duration.ofHours(1))
        .serializeKeysWith(...)
        .serializeValuesWith(...)
        .disableCachingNullValues();

return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(config)
        .build();
```

**ApiResponse static factory methods (a variation of Builder):**
```java
// common/dto/ApiResponse.java
public record ApiResponse<T>(boolean success, String message, T data, LocalDateTime timestamp) {
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, LocalDateTime.now());
    }
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, LocalDateTime.now());
    }
}
```

`ApiResponse.success(data)` and `ApiResponse.error(message)` are named static factory methods — a simplified Builder variant where common configurations have readable names.

### Why Builder Over Constructors

```java
// Constructor — which boolean is which? What does null mean?
new ApiResponse<>(true, "Success", caseData, LocalDateTime.now())

// Named factory — instantly readable
ApiResponse.success(caseData, "Case found")
ApiResponse.error("Case not found")
```

### Node.js Analogy

Method chaining in JavaScript: `Promise.resolve().then(...).catch(...).finally(...)` is builder-style. Mongoose query builder: `User.find().where('email').equals(email).select('-password').lean()` is the same pattern. Axios creating instances with `axios.create({ baseURL, timeout, headers })` is a builder.

---

## 10. Chain of Responsibility Pattern

### The Concept

A request passes through a chain of handlers. Each handler either handles the request or passes it to the next handler. The sender doesn't know which handler will process it.

### In VaadVivaad — `SecurityFilterChain`

```java
// config/SecurityConfig.java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
            .anyRequest().authenticated()
        )
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        )
        .authenticationProvider(authenticationProvider())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

The filter chain for an incoming request to `/api/subscriptions`:

```
HTTP Request: POST /api/subscriptions
    |
    v
[CorsFilter] — adds CORS headers, passes forward
    |
    v
[JwtAuthFilter] — extracts JWT, validates, sets SecurityContext, passes forward
    |
    v
[UsernamePasswordAuthenticationFilter] — checks if form login, passes forward (not applicable)
    |
    v
[FilterSecurityInterceptor] — checks authorization rules (anyRequest().authenticated())
    |
    v
[DispatcherServlet] — routes to SubscriptionController.subscribe()
```

Each filter in `JwtAuthFilter` calls `filterChain.doFilter(request, response)` to pass control forward. If a filter decides the request is invalid (e.g., invalid JWT in a future strict-mode implementation), it can write a `401` response directly and NOT call `filterChain.doFilter()`. The chain stops.

### Node.js Analogy

Express middleware chain is this exact pattern. Each middleware function calls `next()` to pass to the next handler, or sends a response directly to halt the chain. `passport.authenticate()` middleware either sets `req.user` and calls `next()`, or responds with `401`.

---

## 11. Repository Pattern

### The Concept

Abstract data access behind an interface. Business logic talks to the repository interface. The concrete implementation (JPA, MongoDB, in-memory) is hidden. Services are decoupled from the database.

### In VaadVivaad — `CourtCaseRepository`

```java
// lookup/repository/CourtCaseRepository.java
@Repository
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> {
    Optional<CourtCase> findByCnrNumber(String cnrNumber);
    boolean existsByCnrNumber(String cnrNumber);
}
```

`ScraperService` uses it:
```java
// In ScraperService.scrapeOrRefresh()
Optional<CourtCase> existing = courtCaseRepository.findByCnrNumber(cnrNumber);
// ...
CourtCase saved = courtCaseRepository.save(courtCase);
```

`ScraperService` does not know:
- That the underlying database is PostgreSQL
- That Hibernate is the ORM
- How `findByCnrNumber` translates to SQL (`SELECT * FROM court_cases WHERE cnr_number = ?`)
- What connection pool is being used

All it knows is: "I have a `CourtCaseRepository`, and I can call `findByCnrNumber()`, `save()`, `findById()`."

### Benefits for Testing

In unit tests, you mock the repository:
```java
@ExtendWith(MockitoExtension.class)
class ScraperServiceTest {

    @Mock
    private CourtCaseRepository courtCaseRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ScraperService scraperService;

    @Test
    void shouldReturnCachedCaseWhenFresh() {
        CourtCase freshCase = new CourtCase();
        freshCase.setLastScrapedAt(LocalDateTime.now().minusHours(1));
        when(courtCaseRepository.findByCnrNumber("MHCC010012345678"))
                .thenReturn(Optional.of(freshCase));

        CourtCase result = scraperService.scrapeOrRefresh("MHCC010012345678");
        // ...
    }
}
```

No database needed. The Repository pattern makes this possible.

### Node.js Analogy

In Node MVC apps, your Mongoose `User` model with static methods (`User.findByEmail()`, `User.create()`) is a repository. If you use a repository pattern explicitly, you create a `UserRepository` class with `findByEmail()`, `save()`, `delete()` that internally calls Mongoose — and your service depends on `UserRepository`, not Mongoose directly.

---

## 12. SOLID Principles in VaadVivaad

SOLID is not a checklist. Each principle exists to prevent a specific type of pain.

### S — Single Responsibility Principle

One class, one reason to change.

VaadVivaad has `NotificationConsumer` and `SummaryConsumer` as separate classes:

```java
// notification/consumer/NotificationConsumer.java
@Component
public class NotificationConsumer {
    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) { ... }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleHearingReminder(HearingReminderEvent event) { ... }
}
```

```java
// ai/consumer/SummaryConsumer.java
@Component
public class SummaryConsumer {
    @RabbitListener(queues = RabbitMQConfig.SUMMARY_REQUEST_QUEUE)
    public void handleSummaryRequest(SummaryRequestEvent event) { ... }
}
```

Why separate? The `SummaryConsumer.java` comment explains it directly: slow Claude AI calls would block time-sensitive notification processing if they shared a consumer. Also: notification logic changes (add Twilio, add email) do not touch summary logic, and vice versa. One reason to change each.

### O — Open/Closed Principle

Open for extension, closed for modification. Add new behavior by adding new code, not by editing existing code.

`CaptchaResolver` interface enables this:
```java
public interface CaptchaResolver {
    String solve(byte[] captchaImageBytes);
}

// Dev environment — no real captcha solving
@Component @Profile("dev")
public class NoOpCaptchaResolver implements CaptchaResolver { ... }

// Production — add this class without touching anything else:
@Component @Profile("prod")
public class TwoCaptchaResolver implements CaptchaResolver {
    @Override
    public String solve(byte[] imageBytes) {
        // call 2captcha API
    }
}
```

`ScraperService` never changes. You added a new CAPTCHA strategy by adding a new class.

### L — Liskov Substitution Principle

Subclasses/implementations must be substitutable for their base type without breaking the program.

```java
// auth/security/UserDetailsServiceImpl.java
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
```

Spring Security's `DaoAuthenticationProvider` depends on `UserDetailsService` (the interface). It calls `loadUserByUsername()`. Our `UserDetailsServiceImpl` can substitute anywhere `UserDetailsService` is expected — it fulfills the contract exactly. Swapping it with a different `UserDetailsService` implementation (e.g., LDAP-backed) would not break `DaoAuthenticationProvider`.

### I — Interface Segregation Principle

Don't force clients to depend on methods they don't use.

```java
// CourtCaseRepository extends JpaRepository — full CRUD + pagination + sorting
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> { ... }

// For a read-only use case, prefer:
// public interface CourtCaseReadRepository extends CrudRepository<CourtCase, UUID> { ... }
// or even:
// public interface CourtCaseQueryRepository extends Repository<CourtCase, UUID> { ... }
```

Spring Data offers `Repository` (empty marker), `CrudRepository` (basic CRUD), `PagingAndSortingRepository`, and `JpaRepository` (everything). Use only what you need. A service that only reads cases doesn't need `deleteAll()`, `saveAll()`, or batch operations. Declaring a narrower interface makes intent clear and prevents accidental writes.

### D — Dependency Inversion Principle

High-level modules depend on abstractions, not concretions. Abstractions don't depend on details.

```java
// ScraperService (high-level) depends on CaptchaResolver (abstraction)
// NOT on NoOpCaptchaResolver (concretion)
@Service
public class ScraperService {
    // Injected as the CaptchaResolver interface — Spring resolves the active implementation
    // (This would be added in a future iteration where ScraperService uses captcha solving)
    private final CaptchaResolver captchaResolver;

    public ScraperService(/* ... */ CaptchaResolver captchaResolver) {
        this.captchaResolver = captchaResolver;
    }
}
```

Spring's DI container handles this automatically. You declare the dependency as the interface type. Spring finds the active implementation and injects it. High-level business logic has zero coupling to the low-level concretion.

---

## 13. Event-Driven Architecture — The Full VaadVivaad Flow

VaadVivaad uses an event-driven architecture for two independent workflows. Understanding these flows end-to-end is the kind of holistic thinking interviewers test in system design rounds.

### Flow 1: Court Case Scrape → AI Summary

```
User makes API call: POST /api/scraper/refresh/{cnrNumber}
    |
    v
ScraperController → ScraperService.scrapeOrRefresh()
    |  @Transactional starts
    |
    v
ECourtWebClient fetches HTML from eCourts portal
    |
    v
ECourtHtmlParser parses HTML → ParsedCaseData
    |
    v
CourtCaseRepository.save() → persists CourtCase to PostgreSQL
    |
    v
HearingRepository.save() → persists each Hearing to PostgreSQL
    |
    v
For each saved Hearing:
    rabbitTemplate.convertAndSend(SUMMARY_EXCHANGE, SUMMARY_ROUTING_KEY, SummaryRequestEvent)
    |
    v  (message serialized to JSON, sent to RabbitMQ summary.request.queue)
    |
    @Transactional commits

                        [ASYNC — separate thread/process]
                            |
                            v
                    SummaryConsumer.handleSummaryRequest()
                            |
                            v
                    SummaryService.generateAndSave(hearingId)
                            |
                            v
                    ClaudeClient → POST to Anthropic Claude API
                            |
                            v
                    Claude API returns summary text
                            |
                            v
                    Save summary to DB (hearing.summary field or separate table)
```

**Key design decision in `SummaryRequestEvent`:**
```java
public record SummaryRequestEvent(
        UUID hearingId,    // identity only — not full Hearing data
        String cnrNumber   // for logging only
) {}
```

The event carries an ID, not the full entity. This is **event notification** style (as opposed to event-carried state transfer). The consumer fetches fresh data when it processes. Why? Because by the time the consumer processes the message, the DB state is authoritative and fresh. Embedding the full `Hearing` object in the message would mean stale data if anything changed between publish and consume.

### Flow 2: Subscription → Welcome Notification

```
User makes API call: POST /api/subscriptions
    |
    v
SubscriptionController → SubscriptionService.subscribe()
    |  @Transactional starts
    |
    v
SubscriptionRepository.save() → persists Subscription to PostgreSQL
    |
    v
rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, SubscriptionCreatedEvent)
    |
    @Transactional commits

                        [ASYNC — separate thread/process]
                            |
                            v
                    NotificationConsumer.handleSubscriptionCreated()
                            |
                            v
                    Currently: logs the event
                    Future: Twilio WhatsApp/SMS API call
```

### The Outbox Pattern Gap

There is a subtle consistency problem in both flows above. Inside `@Transactional`, we:
1. Save to DB
2. Publish to RabbitMQ

What if step 2 succeeds but the transaction (step 1) rolls back? A message was published to RabbitMQ for data that doesn't exist in the database. The consumer will fetch a non-existent ID.

What if step 1 succeeds but step 2 fails (RabbitMQ is down)? The DB has data but no message was sent. The summary is never generated; the notification is never sent.

This is the classic dual-write problem. See the next section.

---

## 14. Outbox Pattern — Enterprise Fix for the Dual-Write Problem

### The Problem

`@Transactional` guarantees atomicity within a single database. It cannot span a database AND a message broker. The two writes are independent — one can fail while the other succeeds.

VaadVivaad's `ScraperService` even documents this in the code comments:

```
// Production would use transactional outbox pattern to guarantee exactly-once delivery.
```

### The Solution — Outbox Table

Instead of publishing to RabbitMQ inside the transaction, write to an `outbox` table **inside the same transaction**:

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,   -- "Hearing", "Subscription"
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,       -- "SummaryRequestEvent"
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',   -- PENDING, SENT, FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP
);
```

Modified scraper flow with Outbox:

```java
@Transactional
public CourtCase scrapeOrRefresh(String cnrNumber) {
    // ... parse and save ...
    CourtCase saved = courtCaseRepository.save(courtCase);

    for (Hearing savedHearing : savedHearings) {
        // INSTEAD of rabbitTemplate.convertAndSend(...)
        // Write to outbox table — same transaction as DB save
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("Hearing");
        outboxEvent.setAggregateId(savedHearing.getId());
        outboxEvent.setEventType("SummaryRequestEvent");
        outboxEvent.setPayload(toJson(new SummaryRequestEvent(savedHearing.getId(), cnrNumber)));
        outboxRepository.save(outboxEvent); // same transaction!
    }

    return saved; // transaction commits — both court_cases and outbox_events are committed
}

// Separate scheduled poller (runs every second)
@Scheduled(fixedDelay = 1000)
@Transactional
public void pollOutbox() {
    List<OutboxEvent> pending = outboxRepository.findByStatus("PENDING");
    for (OutboxEvent event : pending) {
        try {
            rabbitTemplate.convertAndSend(/* ... from event payload ... */);
            event.setStatus("SENT");
            event.setSentAt(LocalDateTime.now());
        } catch (Exception e) {
            event.setStatus("FAILED");
            // retry logic, dead-letter after N failures
        }
        outboxRepository.save(event);
    }
}
```

### Why VaadVivaad Needs This

Right now, if RabbitMQ is down during a scrape:
- The court case is saved to DB (good)
- The summary request is never published (bad — no AI summary ever)
- The user never gets a proper summary for that hearing

With the Outbox Pattern:
- The court case AND the outbox event are saved atomically
- When RabbitMQ comes back up, the poller picks up PENDING events and publishes them
- Guaranteed delivery — no message is ever lost due to a broker outage

### Outbox vs Current Approach — Trade-offs

| Aspect | Current (Direct Publish) | Outbox Pattern |
|--------|--------------------------|----------------|
| Implementation complexity | Low | Medium-High |
| Delivery guarantee | Best-effort | At-least-once |
| DB load | Lower | Higher (extra table + polling) |
| Correct for MVP | Yes | Overkill for MVP |
| Correct for production | No | Yes |

For VaadVivaad MVP, the current approach is acceptable. For a production system where a missed hearing notification could mean a user misses their court date, the Outbox Pattern is required.

---

## 15. Command Pattern

### The Concept

Encapsulate a request as an object. This allows parameterizing methods, queuing requests, logging them, and supporting undo.

### In Spring MVC

Spring MVC internally treats each HTTP request as a command dispatched to the appropriate controller method. `DispatcherServlet` maintains a `HandlerMapping` (a registry) that maps requests to handler methods. When a request arrives:

1. `DispatcherServlet` asks `HandlerMapping`: "Who handles POST /api/subscriptions?"
2. `HandlerMapping` returns `SubscriptionController.subscribe()` as the command
3. `DispatcherServlet` invokes it (with argument resolution, data binding, etc.)

The controller method is the command object. `DispatcherServlet` is the invoker. The `HandlerMapping` is the registry.

### In Event-Driven Systems

`SummaryRequestEvent` and `SubscriptionCreatedEvent` are also Command objects in disguise. They encapsulate "do this thing" (generate summary for hearing X, send notification to user Y). Putting them on a queue allows:
- Queuing: process when a consumer is available
- Retry: re-enqueue failed commands
- Dead-lettering: park commands that repeatedly fail for inspection

---

## 16. Node.js to Java Pattern Comparison — Quick Reference

| Pattern | Node.js Equivalent | Java/Spring |
|---------|--------------------|-------------|
| Singleton | Module cache (`require()` caches modules) | Spring bean scope (default singleton) |
| Factory | Factory functions, `module.exports = () => config` | `@Bean` methods in `@Configuration` |
| Proxy | ES6 `Proxy`, Express error middleware | CGLIB/JDK Dynamic Proxy for AOP |
| Template Method | Abstract base class, inheritance | `OncePerRequestFilter.doFilterInternal()` |
| Strategy | Function argument (`arr.sort(compareFn)`) | Interface + multiple `@Component` implementations |
| Observer | `EventEmitter.on()` / `.emit()` | Spring Events / `@RabbitListener` |
| Decorator | HOF wrapping, `app.use(middleware)` | `ExchangeFilterFunction`, Java Streams |
| Builder | Method chaining (`query.where().select().lean()`) | `WebClient.builder()`, `QueueBuilder` |
| Chain of Responsibility | Express `next()` middleware chain | Spring `SecurityFilterChain` |
| Repository | Mongoose model, TypeORM repository | `JpaRepository` / Spring Data |
| Command | Redux actions, message queue payloads | Controller handler methods, Event records |

---

## 17. `Auditable` — Template Method + Observer Hybrid

```java
// common/audit/Auditable.java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

`@MappedSuperclass` is Template Method: child entities (`CourtCase`, `User`, `Subscription`) inherit `createdAt` and `updatedAt` fields without re-declaring them. The parent defines the structure; children fill in the domain-specific fields.

`@EntityListeners(AuditingEntityListener.class)` is Observer: the `AuditingEntityListener` observes JPA lifecycle events (`@PrePersist`, `@PreUpdate`). When an entity is saved, the listener fires and automatically populates `@CreatedDate` and `@LastModifiedDate`. The entity doesn't set these fields itself — the observer does it.

This is enabled by:
```java
// VaadVivaadApplication.java
@SpringBootApplication
@EnableScheduling
@EnableCaching
public class VaadVivaadApplication { ... }
```

And `@EnableJpaAuditing` in `JpaAuditingConfig.java`. Without `@EnableJpaAuditing`, the listener is registered but `@CreatedDate` never gets populated.

---

## 18. Interview Q&A — 10 Senior-Level Questions

**Q1: Which design pattern does `@Transactional` use?**

A: Proxy Pattern. Spring generates a CGLIB proxy (subclass) of your bean at startup. The proxy overrides the annotated method to begin a transaction before delegation and commit/rollback after. When other beans inject your service, they receive the proxy. The real object is never directly accessible. This is why calling an `@Transactional` method from within the same class bypasses transaction management — the call goes directly to `this` (the real object), skipping the proxy entirely.

---

**Q2: Why does self-invocation bypass `@Transactional` and `@Cacheable`?**

A: Spring's AOP works via proxy. When Bean A calls Bean B's `@Transactional` method, the call goes through Bean B's proxy — the proxy intercepts and applies advice. But when a method calls another method on `this`, it bypasses the proxy entirely (there's no interception). The proxy wraps the bean from outside; internal `this` calls are direct. Solutions: extract the method to a separate bean, inject `self` (`@Autowired private MyService self`), or use `AopContext.currentProxy()`.

---

**Q3: What is the difference between JDK Dynamic Proxy and CGLIB proxy?**

A: JDK Dynamic Proxy requires the target to implement at least one interface; it creates a proxy that implements those same interfaces. CGLIB creates a subclass of your target class at runtime — no interface required. Spring Boot defaults to CGLIB for `@Service` and `@Component` beans even when interfaces are present (since Spring Boot 2.x). CGLIB cannot proxy `final` classes or `final` methods because subclassing them is impossible in Java.

---

**Q4: How does Spring Security's filter chain work?**

A: Chain of Responsibility Pattern. Each request passes through a chain of `Filter` objects in order. Each filter either handles the request (writes a response and stops) or delegates to the next filter via `filterChain.doFilter(request, response)`. In VaadVivaad: `JwtAuthFilter` runs before `UsernamePasswordAuthenticationFilter`. It extracts the JWT, validates it, sets the `SecurityContext`, and then calls `filterChain.doFilter()` to continue. If the JWT is missing, it still calls `filterChain.doFilter()` — Spring Security's `FilterSecurityInterceptor` then rejects the request based on authorization rules.

---

**Q5: Why does `CaptchaResolver` use an interface instead of a concrete class?**

A: Strategy Pattern + Open/Closed Principle. `ScraperService` depends on the abstraction (interface), not the implementation. In dev, `NoOpCaptchaResolver` (annotated `@Profile("dev")`) is injected. In prod, a `TwoCaptchaResolver` is injected — different class, same contract. Switching strategies requires zero changes to `ScraperService`. Without the interface, ScraperService would need `if/else` or `switch` on profile — a violation of OCP, where every new CAPTCHA provider requires modifying existing code.

---

**Q6: How is `WebClient` a Decorator Pattern?**

A: Each `.filter(ExchangeFilterFunction)` call on `WebClient.builder()` wraps the underlying HTTP exchange with an additional layer. In VaadVivaad, `logRequest()` and `logResponse()` filters are applied. When an HTTP call is made, execution flows: `logRequest` filter → `logResponse` filter → actual HTTP call. Each `ExchangeFilterFunction` receives the request/response, performs its behavior (logging), and passes control to the next function in the chain via `next.exchange(request)`. They all implement the same `ExchangeFilterFunction` interface — same as classic Decorator (same interface, added behavior).

---

**Q7: What is the Outbox Pattern and why does VaadVivaad need it?**

A: The Outbox Pattern solves the dual-write problem: you cannot atomically write to a DB and publish to a message broker in the same transaction. VaadVivaad currently publishes to RabbitMQ inside `@Transactional` — if the publish succeeds but the transaction rolls back, a phantom message exists. If the transaction commits but RabbitMQ is down, the message is lost. The Outbox Pattern writes the event to an `outbox_events` table inside the same DB transaction (atomic), then a separate poller reads pending events and publishes them to RabbitMQ, marking them as sent. This guarantees at-least-once delivery and survives broker outages.

---

**Q8: How does Spring's `@Bean` method relate to the Factory Pattern?**

A: `@Bean` methods in `@Configuration` classes are factory methods. They encapsulate all the object creation and configuration logic, returning a fully-configured instance. `WebClientConfig.eCourtWebClient()` creates an `HttpClient` with timeouts, builds a `WebClient` with a base URL, default headers, and filter chain, and returns the result. Other beans that need the `WebClient` just declare it as a dependency — they have no idea how it was constructed. The `ApplicationContext` itself is a giant factory (bean factory) that manages all these factories and their products.

---

**Q9: Explain the Observer Pattern in VaadVivaad's event flow.**

A: `SubscriptionService` (publisher) publishes a `SubscriptionCreatedEvent` to RabbitMQ's `vaadvivaad.exchange` after saving a subscription. `NotificationConsumer` (observer) listens on `notification.queue` via `@RabbitListener`. The publisher has no import of, no dependency on, and no direct knowledge of `NotificationConsumer`. Decoupling is complete. You could add a second consumer listening to the same queue for analytics/logging without touching `SubscriptionService` at all. The event record `SubscriptionCreatedEvent` is the message contract — carry enough data for the consumer to act, but send identity (IDs) rather than full entity objects.

---

**Q10: How does `Auditable` use the Template Method Pattern?**

A: `Auditable` is a `@MappedSuperclass` — not a DB table itself, but its fields are mapped into every entity table that extends it. It defines the structure (template): `createdAt` and `updatedAt` fields with `@CreatedDate` / `@LastModifiedDate`. Every entity extends `Auditable` and gets these fields automatically without redeclaring them. The template defines what every entity has in common; each entity class adds its domain-specific fields. `AuditingEntityListener` (registered via `@EntityListeners`) observes JPA lifecycle hooks and automatically populates these fields on persist/update — combining Template Method with the Observer pattern.

---

## Senior Differentiators

These are the things that separate a developer who *uses* Spring from one who *understands* Spring. Interviewers know the difference.

### Know the proxy internals
Not just "Spring creates a proxy." Know: CGLIB subclasses your class, JDK proxy implements your interface, `final` methods/classes cannot be proxied, and self-invocation bypasses all proxy-based AOP.

### Know when the pattern breaks
`@Transactional` on a private method does nothing. `@Cacheable` on a method called from within the same class does nothing. `@Async` on a method invoked without the proxy does nothing. These are proxy bypass scenarios. Knowing the failure mode shows you understand the mechanism, not just the annotation.

### Distinguish the event styles
Event notification (carry IDs, consumer fetches state) vs. event-carried state transfer (carry full data). Know when to use each. VaadVivaad uses notification style for `SummaryRequestEvent` — freshness matters more than avoiding the DB round-trip.

### Name the gap in your own code
VaadVivaad's `ScraperService` comment acknowledges the outbox pattern gap. This is senior behavior: knowing your code's limitations and being able to articulate exactly what would need to change for production-grade reliability. Interviewers respect engineers who reason about trade-offs.

### Connect patterns to framework features
Not: "I use `@Transactional`."
Yes: "`@Transactional` uses Proxy Pattern implemented via CGLIB. It's an Around advice in Spring's AOP framework. The proxy intercepts the method call, delegates to `PlatformTransactionManager.getTransaction()`, calls your method, and then calls `commit()` or `rollback()` based on whether an exception was thrown. The exception type matters — only `RuntimeException` triggers rollback by default; checked exceptions do not unless you set `rollbackFor`."

### Know the SOLID application to your code
Not: "I know SOLID principles."
Yes: "In VaadVivaad, `CaptchaResolver` interface embodies both OCP and DIP. Adding a `TwoCaptchaResolver` in production requires zero modification to `ScraperService` — that's OCP. `ScraperService` depending on the interface rather than the implementation is DIP. The `@Profile('dev')` on `NoOpCaptchaResolver` is how Spring resolves the correct strategy at runtime."

### Thread safety and statelessness
Spring beans are singletons. Shared by all threads. Stateful instance fields are dangerous. The right mental model: every service method runs as if it's a stateless function. Input comes from parameters. Output is the return value. All state lives in the database. Local variables are safe; instance variables are shared.

---

*File: `11_design_patterns_spring.md` | Project: VaadVivaad | Spring Boot 3.4.4 / Java 21*
