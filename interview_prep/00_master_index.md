# Senior Java/Spring Boot Interview Prep — Master Index
### Anchored to VaadVivaad (Spring Boot 3.4 + PostgreSQL + RabbitMQ + Redis + Claude AI)

---

## The Core Problem Java Feels Mechanical — And How to Fix It

| Node/Express (explicit) | Spring Boot (declarative) |
|---|---|
| `app.use(cors())` | `@Bean CorsConfigurationSource` |
| `app.use(authenticate)` | `@Component JwtAuthFilter extends OncePerRequestFilter` |
| `const pool = new Pool(config)` | `@Repository` + Spring Data auto-wires DataSource |
| `try { await db.query() } catch` | `@Transactional` + `@ControllerAdvice` |
| `app.listen(3000)` | `@SpringBootApplication` + embedded Tomcat |

**The unlock:** In Node you wire things. In Spring, you *declare* things and the **ApplicationContext** (IoC container) wires them. Once you see Spring as "a framework that manages your object graph", every annotation makes logical sense.

---

## Study Order (Phase by Phase)

```
Phase 1 → Mental model first. Everything else depends on this.
Phase 2 → Go file by file through VaadVivaad. Understand what each annotation does.
Phase 3 → Senior differentiators. What separates mid from senior in Java interviews.
```

---

## File Index

| File | Phase | Topics | Interview Weight |
|------|-------|---------|-----------------|
| [01_ioc_di_application_context.md](./01_ioc_di_application_context.md) | 1 — Foundation | IoC, DI, Beans, ApplicationContext, Auto-configuration | ⭐⭐⭐⭐⭐ Every round |
| [02_spring_security_jwt.md](./02_spring_security_jwt.md) | 2 — Core | SecurityFilterChain, JwtAuthFilter, UserDetails, CORS | ⭐⭐⭐⭐⭐ Every round |
| [03_jpa_entities_repositories_flyway.md](./03_jpa_entities_repositories_flyway.md) | 2 — Core | Entities, relationships, FetchType, JPQL, Flyway | ⭐⭐⭐⭐⭐ Every round |
| [04_transactions_service_layer.md](./04_transactions_service_layer.md) | 2 — Core | @Transactional, propagation, isolation, rollback | ⭐⭐⭐⭐⭐ Every round |
| [05_rabbitmq_messaging.md](./05_rabbitmq_messaging.md) | 2 — Core | AMQP, exchanges, queues, DLQ, @RabbitListener | ⭐⭐⭐⭐ Senior bar |
| [06_redis_caching.md](./06_redis_caching.md) | 2 — Core | @Cacheable, @CacheEvict, TTL, cache-aside pattern | ⭐⭐⭐⭐ Senior bar |
| [07_webclient_reactive.md](./07_webclient_reactive.md) | 2 — Core | WebClient, Mono/Flux, error handling, timeouts | ⭐⭐⭐⭐ Senior bar |
| [08_scheduling_async.md](./08_scheduling_async.md) | 2 — Core | @Scheduled, cron, @Async, CompletableFuture | ⭐⭐⭐ Cloud/backend roles |
| [09_exception_handling_validation.md](./09_exception_handling_validation.md) | 2 — Core | @ControllerAdvice, @ExceptionHandler, JSR-380, ApiResponse | ⭐⭐⭐⭐ Every round |
| [10_jpa_performance.md](./10_jpa_performance.md) | 3 — Senior | N+1, JOIN FETCH, projections, EXPLAIN ANALYZE, indexes | ⭐⭐⭐⭐⭐ Senior bar |
| [11_design_patterns_spring.md](./11_design_patterns_spring.md) | 3 — Senior | AOP/Proxy, Factory, Observer (Events), Template Method | ⭐⭐⭐⭐⭐ Senior bar |
| [12_testing.md](./12_testing.md) | 3 — Senior | @SpringBootTest, @WebMvcTest, @DataJpaTest, Mockito | ⭐⭐⭐⭐ Senior bar |
| [13_microservices_concepts.md](./13_microservices_concepts.md) | 3 — Senior | Circuit breaker, service discovery, API gateway, Saga | ⭐⭐⭐⭐ Final rounds |

---

## VaadVivaad Architecture at a Glance

```
┌──────────────────────────────────────────────────────────────────┐
│                     FRONTEND (React — placeholder)                │
└──────────────────────────────┬───────────────────────────────────┘
                               │ HTTP (REST)
┌──────────────────────────────▼───────────────────────────────────┐
│                   BACKEND (Spring Boot 3.4 / Java 21)             │
│                                                                    │
│  JwtAuthFilter → SecurityFilterChain                               │
│       ↓                                                            │
│  @RestController (thin — HTTP only)                                │
│       ↓                                                            │
│  @Service (business logic, @Transactional, events)                 │
│       ↓                                                            │
│  @Repository (Spring Data JPA — auto-implemented)                  │
│       ↓                                                            │
│  PostgreSQL (Flyway migrations, Hibernate ORM)                     │
│                                                                    │
│  @RabbitListener ← RabbitMQ ← ApplicationEventPublisher           │
│  @Cacheable/@CacheEvict ← Redis                                    │
│  WebClient → eCourts (scraping) + Claude API (AI summaries)        │
│  @Scheduled → 6AM scrape + 8AM reminders                           │
└──────────────────────────────────────────────────────────────────┘
```

---

## Node → Java Mental Model Map (Quick Reference)

| Concept | Node/Express | Java/Spring Boot |
|---|---|---|
| App entry point | `server.js` | `VaadVivaadApplication.java` + `@SpringBootApplication` |
| Object creation | `const service = new Service()` | `@Service` → Spring creates + injects |
| Middleware | `app.use(fn)` | `@Component extends OncePerRequestFilter` |
| Route handler | `router.get('/path', fn)` | `@GetMapping("/path")` on `@RestController` |
| Auth middleware | `authenticate` function | `JwtAuthFilter` + `SecurityFilterChain` |
| DB query | `pool.query('SELECT...', [id])` | `@Repository` + Spring Data / JPQL |
| ORM model | Sequelize/Prisma model | `@Entity` + `@Table` + JPA |
| Async function | `async/await` | `@Async` / `CompletableFuture` / `WebClient` (Mono) |
| Error handler | `(err, req, res, next)` | `@ControllerAdvice` + `@ExceptionHandler` |
| Environment vars | `process.env.DB_HOST` | `${DB_HOST}` in `application.yml` |
| Config object | `dotenv` + `config.js` | `@ConfigurationProperties` |
| Event emitter | `EventEmitter.emit()` | `ApplicationEventPublisher.publishEvent()` |
| Background job | `setInterval` / `cron` lib | `@Scheduled(cron = "0 0 6 * * *")` |
| HTTP client | `axios` | `WebClient` (reactive) |
| Message queue | `bull` / `amqplib` | Spring AMQP + `@RabbitListener` |
| Caching | `node-cache` / Redis manually | `@Cacheable` + Spring Cache abstraction |
| Test runner | Jest + supertest | JUnit 5 + MockMvc + Mockito |
| Integration test | supertest with real server | `@SpringBootTest` + `@AutoConfigureMockMvc` |

---

## VaadVivaad File → Concept Map (Phase 2 Reference)

| File in VaadVivaad | What It Teaches |
|---|---|
| `VaadVivaadApplication.java` | `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` |
| `config/SecurityConfig.java` | SecurityFilterChain, stateless sessions, CORS, public vs protected routes |
| `auth/security/JwtAuthFilter.java` | `OncePerRequestFilter`, SecurityContext, token extraction |
| `auth/security/JwtService.java` | JJWT library, signing keys, claims, token validation |
| `auth/security/UserDetailsServiceImpl.java` | `UserDetailsService`, `UserDetails`, Spring Security integration |
| `config/RabbitMQConfig.java` | DirectExchange, Queue, Binding, DLQ pattern |
| `notification/consumer/NotificationConsumer.java` | `@RabbitListener`, `@Payload`, message acknowledgement |
| `ai/consumer/SummaryConsumer.java` | Slow consumer isolation, separate thread pool |
| `config/RedisConfig.java` | `RedisCacheManager`, TTL, serialization |
| `lookup/service/CaseLookupService.java` | `@Cacheable`, `@CacheEvict`, `@Transactional(readOnly=true)` |
| `scraper/service/ScraperService.java` | `@Transactional`, upsert pattern, event publishing |
| `subscription/service/SubscriptionService.java` | `ApplicationEventPublisher`, domain events |
| `config/WebClientConfig.java` | `WebClient.Builder`, base URLs, filters, timeouts |
| `scraper/client/ECourtWebClient.java` | WebClient usage, `Mono`, error handling, rate limiting |
| `ai/client/ClaudeClient.java` | External API client, `bodyToMono`, request/response DTOs |
| `notification/scheduler/NotificationScheduler.java` | `@Scheduled`, cron expressions, `@EnableScheduling` |
| `common/exception/GlobalExceptionHandler.java` | `@ControllerAdvice`, multiple `@ExceptionHandler`, problem detail |
| `user/entity/User.java` | `@Entity`, `@Id`, `@Column`, `@Enumerated`, `Auditable` base class |
| `lookup/entity/CourtCase.java` | `@OneToMany`, `FetchType.LAZY`, cascade |
| `lookup/entity/Hearing.java` | `@ManyToOne`, `@JoinColumn`, null handling |
| `lookup/repository/CourtCaseRepository.java` | `JpaRepository`, derived queries, `@Query` (JPQL) |
| `db/migration/V*.sql` | Flyway versioned migrations, checksums, seed data |

---

## The 10 Questions That Always Come Up (Java/Spring Senior)

**1. "What is Dependency Injection and how does Spring implement it?"**
> Spring's IoC container manages bean lifecycle. `@Autowired` (or constructor injection) tells Spring to inject a managed bean. Under the hood, Spring creates a proxy or direct instance and injects it. Constructor injection is preferred — it makes dependencies explicit and enables immutability. See `01_ioc_di_application_context.md`.

**2. "Walk me through what happens when a request hits your Spring Boot app."**
> HTTP request → embedded Tomcat → `DispatcherServlet` → Filter chain (JwtAuthFilter) → SecurityContext populated → `@RestController` matched by `@RequestMapping` → `@Valid` triggers JSR-380 validation → Service called → Repository queries DB → response serialized to JSON by Jackson.

**3. "How does `@Transactional` work?"**
> Spring wraps the method in a proxy. On entry: begins transaction. On success: commits. On unchecked exception: rolls back. Propagation controls what happens when a transactional method calls another (default: `REQUIRED` — join existing or create new). See `04_transactions_service_layer.md`.

**4. "What is the N+1 problem and how do you fix it?"**
> Fetching 10 cases, then lazy-loading hearings for each = 11 queries (1 + 10). Fix: `JOIN FETCH` in JPQL, or `@EntityGraph`, or `FetchType.EAGER` (carefully). See `10_jpa_performance.md`.

**5. "How does Spring Security work in this project?"**
> `JwtAuthFilter` extends `OncePerRequestFilter`. On each request: extract Bearer token → `JwtService.validateToken()` → load `UserDetails` from DB → set `UsernamePasswordAuthenticationToken` in `SecurityContextHolder` → downstream code can call `SecurityContextHolder.getContext().getAuthentication()`. See `02_spring_security_jwt.md`.

**6. "What is the difference between `@Component`, `@Service`, `@Repository`, and `@Controller`?"**
> All are `@Component` specializations — functionally equivalent for DI. Semantic differences: `@Repository` adds exception translation (DataAccessException). `@Service` signals business layer. `@Controller`/`@RestController` signals web layer (adds `@ResponseBody`). Use them for readability and AOP pointcut targeting.

**7. "What is RabbitMQ and why use it here instead of direct calls?"**
> RabbitMQ decouples producers from consumers. In VaadVivaad: scraper finishes → publishes `SummaryRequestEvent` to queue → Claude consumer picks it up independently. Benefits: scraper doesn't wait for slow Claude API (2-5s), failures don't block scraping, retries via DLQ. See `05_rabbitmq_messaging.md`.

**8. "What does `@Cacheable` do and what are its risks?"**
> Intercepts method call → checks Redis for cached result → returns cache hit or calls method and stores result. Risks: stale data (fix with `@CacheEvict` on writes), cache stampede (many misses at once), cold start (empty cache on deploy). See `06_redis_caching.md`.

**9. "How would you test a Spring Boot service?"**
> Unit test with Mockito (`@ExtendWith(MockitoExtension.class)`) — mock repository, test service logic. Integration test with `@DataJpaTest` — real H2/Postgres, test queries. Controller test with `@WebMvcTest` + `MockMvc` — test HTTP layer without full context. Full stack with `@SpringBootTest`. See `12_testing.md`.

**10. "What is Flyway and why use it over `ddl-auto: create`?"**
> Flyway tracks and applies versioned SQL migrations. `ddl-auto: create` drops/recreates schema on startup — destroys production data. Flyway is immutable history: run migrations forward only, checksums prevent editing history. Same principle as git commits. See `03_jpa_entities_repositories_flyway.md`.

---

## Senior vs Mid-Level Differentiators (Java/Spring)

Things a senior says that a mid-level doesn't:

- **"I use constructor injection, not field injection"** — field injection hides dependencies, breaks immutability, harder to test
- **"I'd check the propagation here"** — calling `@Transactional` method from within same class bypasses proxy (self-invocation trap)
- **"This will cause an N+1"** — spots lazy loading in a loop without `JOIN FETCH`
- **"The `@Cacheable` here needs `@CacheEvict` on the write path"** — stale cache is a bug
- **"Separate consumers for slow and fast operations"** — Claude calls (2-5s) shouldn't block notification delivery
- **"I'd use a transactional outbox here"** — RabbitMQ publish inside `@Transactional` can send phantom messages on rollback
- **"This `FetchType.EAGER` will break at scale"** — always loading all hearings even when you only need case metadata
- **"The Claude API key is in application-dev.yml"** — flags security issues proactively
- **"Flyway checksums will break if you edit this migration"** — knows the immutability rule
- **"I'd add `readOnly=true` here"** — `@Transactional(readOnly=true)` on query-only methods: Hibernate optimization + connection pool hint

---

## Interview Day Cheat Sheet

**When asked about this project:**
- "It's a real-time Indian court case tracker — Spring Boot backend, PostgreSQL, RabbitMQ for async notifications, Redis for caching, Claude API for Hindi summaries"
- "I structured it with modular packages — auth, lookup, subscription, notification, scraper, AI — each with its own controller/service/repository"
- "The notification pipeline is event-driven: scraper publishes to RabbitMQ, consumers process independently"
- "Flyway manages all schema migrations — 8 migrations from schema creation through seed data fixes"
- "Frontend is planned React but currently a placeholder — backend is the MVP focus"

**When asked to improve it:**
- Add transactional outbox pattern (fix RabbitMQ + @Transactional gap)
- Real CAPTCHA resolver for eCourts (currently NoOp)
- Enable Claude AI summaries (move API key to env var, remove early return in SummaryService)
- Implement frontend (React)
- Add Twilio for WhatsApp/SMS
- Add Prometheus + Grafana metrics
- Add `@PreAuthorize` for method-level security
- Add pagination to all list endpoints
- Circuit breaker (Resilience4j) for eCourts and Claude API calls
