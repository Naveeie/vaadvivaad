# IoC, DI, and the ApplicationContext — Complete Interview Reference

> Target: Senior Java/Spring Boot interviews. Written for a developer fluent in Node.js/React who
> finds Java "mechanical." Every concept here is explained from first principles with a Node
> analogy first, then the Spring answer, then code from **VaadVivaad** (Spring Boot 3.4.4, Java 21).

---

## Table of Contents

1. [The Problem IoC Solves](#1-the-problem-ioc-solves)
2. [Inversion of Control (IoC)](#2-inversion-of-control-ioc)
3. [Dependency Injection (DI)](#3-dependency-injection-di)
4. [The ApplicationContext — the IoC Container](#4-the-applicationcontext--the-ioc-container)
5. [@SpringBootApplication — what three annotations it composes](#5-springbootapplication--what-three-annotations-it-composes)
6. [Bean Annotations — @Component family](#6-bean-annotations--component-family)
7. [@Bean methods in @Configuration classes](#7-bean-methods-in-configuration-classes)
8. [Bean Scope](#8-bean-scope)
9. [Auto-configuration](#9-auto-configuration)
10. [@ConfigurationProperties](#10-configurationproperties)
11. [@Profile — dev vs prod config](#11-profile--dev-vs-prod-config)
12. [Bean Lifecycle](#12-bean-lifecycle)
13. [The Self-invocation Trap](#13-the-self-invocation-trap)
14. [Node → Java Comparison Table](#14-node--java-comparison-table)
15. [Senior Interview Q&A — 10 Pairs](#15-senior-interview-qa--10-pairs)
16. [Senior Differentiators](#16-senior-differentiators)

---

## 1. The Problem IoC Solves

### The Node analogy first

Imagine every Express route handler managed its own database connection:

```javascript
// Node — the anti-pattern
app.get('/cases', async (req, res) => {
  // new connection on EVERY request
  const db = new Pool({
    host: 'localhost',
    database: 'vaadvivaad',
    password: process.env.DB_PASS,
  });
  const result = await db.query('SELECT * FROM court_cases');
  res.json(result.rows);
});

app.post('/cases', async (req, res) => {
  // another new connection — same config copy-pasted
  const db = new Pool({ host: 'localhost', database: 'vaadvivaad', ... });
  await db.query('INSERT INTO court_cases ...');
  res.json({ ok: true });
});
```

You would never do this. You export a single `db` module and import it everywhere. That module is created once, shared, and you do not control when it is created inside each handler — the module system handles that for you. That instinct is exactly IoC.

### The Java equivalent before Spring

```java
// Java — the anti-pattern (pre-Spring thinking)
public class CaseLookupController {

    public CaseResponse lookup(String cnr) {
        // tightly coupled: controller KNOWS about every dependency's constructor
        CourtCaseRepository repo = new CourtCaseRepository(
            new DataSource("jdbc:postgresql://localhost/vaadvivaad", "user", "pass")
        );
        HearingRepository hearingRepo = new HearingRepository(
            new DataSource("jdbc:postgresql://localhost/vaadvivaad", "user", "pass")
        );
        CaseLookupService service = new CaseLookupService(repo, hearingRepo);
        return service.lookupByCnr(cnr);
    }
}
```

Problems with this approach:

**Tight coupling.** `CaseLookupController` must know the full constructor chain of `CaseLookupService`, which knows the full constructor chain of its repositories, which know how to build a `DataSource`. Change the `DataSource` constructor and you update code in every class that touches it.

**Untestable.** You cannot write a unit test for `CaseLookupController` without spinning up a real PostgreSQL database, because the controller hard-codes the real `DataSource`. You cannot swap in a mock.

**No lifecycle management.** You create a new `DataSource` (and by extension a new connection pool) on every single request. Connection pools are expensive objects that should be created once and reused.

**Configuration scattered.** The database URL and credentials live embedded in business logic, not in a configuration file.

**The core insight:** the problem is not Java. The problem is that your business logic classes are responsible for *building* their own collaborators. That is one responsibility too many. Strip object creation out of business logic and put it somewhere else — that "somewhere else" is the IoC container.

---

## 2. Inversion of Control (IoC)

### Definition

**Before IoC:** your code calls `new SomeService()` — *your code controls object creation.*

**After IoC:** you declare what you need, and a framework creates it and hands it to you — *you invert control of object creation to the framework.*

The word "inversion" means the direction of the call has flipped. Previously your code called the framework/library. Now the framework calls your code (by instantiating it and injecting things into it).

### The Hollywood Principle

IoC is sometimes called the Hollywood Principle: "Don't call us, we'll call you." You register your class with Spring. Spring decides when to create it, what to pass to its constructor, and when to destroy it.

### Before and after

```java
// BEFORE — you control creation
public class AuthController {
    private AuthService authService;

    public AuthController() {
        UserRepository userRepo = new UserRepository(...);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwtService = new JwtService();
        AuthenticationManager authManager = ...; // complex setup
        this.authService = new AuthService(userRepo, encoder, jwtService, authManager);
    }
}
```

```java
// AFTER — Spring controls creation
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    // Spring reads this constructor and injects AuthService automatically
    public AuthController(AuthService authService) {
        this.authService = authService;
    }
}
```

`AuthController` no longer knows or cares how `AuthService` is built. It just declares "I need one." Spring builds the entire graph.

---

## 3. Dependency Injection (DI)

DI is the *mechanism* Spring uses to implement IoC. "Inject a dependency" means: pass a collaborating object into a class rather than having that class create it.

There are three injection styles. Only one is recommended.

### Constructor Injection (recommended)

```java
// From VaadVivaad — AuthService.java
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }
}
```

Why constructor injection is the right choice:

- **Immutability.** Fields are `final`. Once constructed, the object's dependencies cannot change. This makes the object thread-safe with no extra effort.
- **Explicit contract.** Looking at the constructor, you know exactly what this class needs. There is no hidden state discovered at runtime.
- **Testable without Spring.** You can `new AuthService(mockRepo, mockEncoder, mockJwt, mockAuth)` in a JUnit test. No Spring context needed at all.
- **Fails fast.** If Spring cannot find a bean for one of the constructor parameters, the application refuses to start. You catch the error at startup, not at runtime when a user hits the endpoint.
- **No circular dependency hiding.** Circular dependencies at constructor time produce a clear error at startup. Field injection hides circular dependencies until runtime.

Notice: with a single constructor in Spring Boot 3.x, you do not even need `@Autowired`. Spring detects the single constructor automatically.

### Field Injection (do not use)

```java
// Anti-pattern — don't do this
@Service
public class CaseLookupService {

    @Autowired  // Spring injects via reflection AFTER construction
    private CourtCaseRepository courtCaseRepository;

    @Autowired
    private HearingRepository hearingRepository;
}
```

Problems:
- Fields cannot be `final` — mutability is introduced silently.
- You cannot test without a Spring context (or reflection hacks).
- Dependencies are hidden — you cannot tell what a class needs without reading every field.
- IntelliJ IDEA flags this with a warning for these exact reasons.

### Setter Injection (use only for optional dependencies)

```java
@Service
public class NotificationService {

    private EmailSender emailSender;

    @Autowired(required = false)  // optional — works without it
    public void setEmailSender(EmailSender emailSender) {
        this.emailSender = emailSender;
    }
}
```

Setter injection is appropriate when a dependency is genuinely optional and has a sensible default behavior when absent. It should not be the default.

### The VaadVivaad pattern

Every service and component in VaadVivaad uses constructor injection with `final` fields. Look at `CaseLookupService`, `AuthService`, `SummaryService`, `NotificationScheduler`, `JwtAuthFilter`, `SecurityConfig` — all the same pattern. This is the correct pattern and it is consistent throughout the codebase.

---

## 4. The ApplicationContext — the IoC Container

### What it is

The **ApplicationContext** is the central object in a Spring application. Think of it as a smart registry that:

1. Knows about every bean (managed object) in your application.
2. Creates beans in the right order (respecting dependencies).
3. Injects dependencies into beans.
4. Manages bean lifecycle (init, destroy).
5. Provides cross-cutting services: event publishing, AOP proxying, internationalization.

The Node.js analogy: imagine a global module registry that introspects all your `require()` calls, creates modules in dependency order, and caches them as singletons. That is roughly what `ApplicationContext` does.

### What happens when Spring boots — step by step

```
SpringApplication.run(VaadVivaadApplication.class, args)
         |
         v
1. BOOTSTRAP PHASE
   - Create the ApplicationContext (AnnotationConfigServletWebServerApplicationContext for web apps)
   - Load environment: system properties, application.yml, application-dev.yml

         |
         v
2. COMPONENT SCAN
   - Start from com.vaadvivaad (the package of VaadVivaadApplication)
   - Find every class annotated with @Component, @Service, @Repository,
     @Controller, @RestController, @Configuration
   - Register them as BeanDefinitions (blueprints, not instances yet)

         |
         v
3. AUTO-CONFIGURATION
   - Read META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
   - For each auto-config class, check @ConditionalOn* conditions
   - If postgresql driver is on classpath AND no DataSource bean exists → create one
   - If RabbitMQ starter is on classpath AND connection config present → create ConnectionFactory
   - Register these as additional BeanDefinitions

         |
         v
4. BEAN INSTANTIATION
   - Determine creation order (topological sort by dependency graph)
   - Instantiate each bean:
       a. Call the constructor (or @Bean factory method)
       b. Inject constructor dependencies (which are already-created beans)
   - Beans are stored in the container by name and type

         |
         v
5. POST-PROCESSORS
   - BeanPostProcessor hooks run
   - @Autowired fields are processed (if any field injection exists)
   - @PostConstruct methods run
   - AOP proxies are created (wrapping beans that have @Transactional, @Cacheable, etc.)

         |
         v
6. CONTEXT READY
   - ApplicationReadyEvent fired
   - Server starts listening on port 8080
   - All @Scheduled tasks begin
```

This is why Spring apps take a few seconds to start — they are doing all of this work upfront. The payoff is that once running, everything is wired, validated, and ready.

### Accessing the context (you rarely need to)

```java
// Programmatic access — a smell in most cases, but valid for dynamic lookups
@Component
public class DynamicResolver {

    private final ApplicationContext context;

    public DynamicResolver(ApplicationContext context) {
        this.context = context;
    }

    public Object resolveByName(String beanName) {
        return context.getBean(beanName);  // runtime lookup
    }
}
```

If you find yourself doing `context.getBean()` frequently, it usually means your design has something that should be an injected dependency.

---

## 5. @SpringBootApplication — What Three Annotations It Composes

```java
// VaadVivaadApplication.java
@SpringBootApplication   // <-- this single annotation is three annotations in one
@EnableScheduling        // additional — enables @Scheduled
@EnableCaching           // additional — enables @Cacheable
public class VaadVivaadApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaadVivaadApplication.class, args);
    }
}
```

`@SpringBootApplication` is a *composed annotation* — it is shorthand for three annotations that must always appear together. Understanding what each does is essential.

### @Configuration

```java
// Equivalent meaning:
@Configuration
// "This class is a source of bean definitions."
// Spring will look for @Bean methods inside it.
// It will process this class as a configuration class, not a regular component.
```

`@Configuration` tells Spring: this class can define beans using `@Bean` methods. It also enables full CGLIB proxying of the class, which means `@Bean` method calls within the class go through the container (important for the singleton guarantee — see Section 7).

### @EnableAutoConfiguration

```java
// Equivalent meaning:
@EnableAutoConfiguration
// "Scan the classpath. For everything you find, apply sensible defaults."
// spring-boot-starter-data-jpa on classpath? → auto-configure DataSource, EntityManagerFactory
// spring-boot-starter-security on classpath? → auto-configure a default security filter chain
// spring-rabbitmq on classpath? → auto-configure ConnectionFactory
// You can override any auto-configured bean with your own @Bean.
```

This is the "magic" that makes Spring Boot feel like it reads your mind. It reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — a list of auto-configuration classes. Each one has `@Conditional` checks. If the conditions are met, the config applies.

### @ComponentScan

```java
// Equivalent meaning:
@ComponentScan
// "Recursively scan the package of this class and all sub-packages."
// Find every @Component, @Service, @Repository, @Controller, @RestController, @Configuration
// Register them as beans in the ApplicationContext.
```

Because `VaadVivaadApplication` is in `com.vaadvivaad`, the scan covers:
- `com.vaadvivaad.auth.*` → `AuthService`, `JwtService`, `JwtAuthFilter`, `UserDetailsServiceImpl`
- `com.vaadvivaad.config.*` → `SecurityConfig`, `RabbitMQConfig`, `RedisConfig`, `WebClientConfig`
- `com.vaadvivaad.lookup.*` → `CaseLookupService`, `CaseLookupController`
- `com.vaadvivaad.notification.*` → `NotificationScheduler`, `NotificationConsumer`
- `com.vaadvivaad.ai.*` → `SummaryService`, `ClaudeClient`, `SummaryConsumer`

If you put a class *outside* `com.vaadvivaad`, it will not be found by the scan. This is a common mistake when copying code into the wrong package.

### The additional annotations

`@EnableScheduling` is what makes `@Scheduled(cron = "0 0 8 * * *")` in `NotificationScheduler` actually run. Without it, the annotation is silently ignored. Same logic for `@EnableCaching` — it enables `@Cacheable` and `@CacheEvict` processing in `CaseLookupService`.

---

## 6. Bean Annotations — @Component Family

All four "stereotype" annotations are aliases for `@Component`. They all result in the class being registered as a Spring bean. The differences are semantic and in some cases functional.

```
@Component
    |
    |─── @Service        (business logic layer)
    |─── @Repository     (data access layer)
    |─── @Controller     (MVC web layer)
         |─── @RestController  (@Controller + @ResponseBody)
```

### What is actually different

**@Repository** — Spring adds automatic exception translation. If Hibernate throws a `PersistenceException`, Spring catches it and wraps it in a `DataAccessException`. This makes repository exceptions consistent regardless of the underlying ORM. `CourtCaseRepository`, `HearingRepository`, `UserRepository` benefit from this.

**@RestController** — shorthand for `@Controller + @ResponseBody`. Every method's return value is written directly to the HTTP response body as JSON (via Jackson). Without `@ResponseBody`, Spring MVC would try to find a view template named after the return value.

**@Service and @Component** — no runtime difference. The distinction is semantic: `@Service` signals business logic, `@Component` signals a generic Spring-managed component. This matters for:
- Code readability
- AOP pointcut targeting (you can write `execution(* @Service *(..))` to apply advice to all service classes)
- Team conventions

### VaadVivaad examples

```java
// @Service — business logic
@Service
public class AuthService { ... }

@Service
public class CaseLookupService { ... }

@Service
public class SummaryService { ... }

// @Service — declared as @Service, implements a Spring Security interface
@Service
public class UserDetailsServiceImpl implements UserDetailsService { ... }

// @Service — JWT utility — declared @Service for DI purposes
@Service
public class JwtService { ... }

// @Component — generic Spring component, not specifically "service" logic
@Component
public class JwtAuthFilter extends OncePerRequestFilter { ... }

@Component
public class NotificationScheduler { ... }
```

### The AOP targeting use case (senior detail)

You can write AOP advice that targets only `@Service` beans:

```java
@Aspect
@Component
public class ServiceLoggingAspect {

    // Only intercepts methods on @Service-annotated classes
    @Around("@within(org.springframework.stereotype.Service)")
    public Object logServiceCall(ProceedingJoinPoint pjp) throws Throwable {
        log.info("Entering: {}", pjp.getSignature());
        Object result = pjp.proceed();
        log.info("Exiting: {}", pjp.getSignature());
        return result;
    }
}
```

This is why stereotypes matter even when they have no runtime behavior differences on their own.

---

## 7. @Bean Methods in @Configuration Classes

### The rule

Use `@Component` (and its stereotypes) when Spring can discover and manage the class by scanning it — i.e., it is *your* class that you own.

Use `@Bean` inside a `@Configuration` class when:
- You need to configure a third-party class you cannot annotate (you do not own `RabbitTemplate`, `WebClient`, `CacheManager`)
- You need to run initialization code to construct the bean
- You need conditional bean creation
- You need to wire multiple @Bean methods together with parameters

### VaadVivaad: why RabbitMQConfig uses @Bean everywhere

```java
// config/RabbitMQConfig.java
@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder
                .bind(notificationQueue())   // <-- calls another @Bean method
                .to(exchange())              // <-- calls another @Bean method
                .with(ROUTING_KEY);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
```

`Queue`, `DirectExchange`, `Binding`, `RabbitTemplate` are all Spring AMQP classes — you cannot annotate them with `@Component`. You must construct them with specific arguments. `@Bean` methods are the only way to do this.

Notice `notificationBinding()` calls `notificationQueue()` and `exchange()` — both other `@Bean` methods. Because `RabbitMQConfig` is annotated with `@Configuration` (which triggers CGLIB subclassing), these calls go through the Spring proxy. They do NOT create new instances — they return the already-registered singleton beans. This is the singleton guarantee.

### VaadVivaad: why RedisConfig uses @Bean

```java
// config/RedisConfig.java
@Configuration
public class RedisConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY
        );
        // ... build RedisCacheConfiguration, return RedisCacheManager
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
```

`CacheManager` is a Spring Data Redis type you cannot annotate. More importantly, building it correctly requires 10+ lines of configuration. A `@Bean` method is the natural place for this initialization code. Spring auto-configures a basic `RedisCacheConfiguration` by default, but VaadVivaad overrides it to add Java 8 time support and type information in serialized values — that override is the `@Bean` method.

### VaadVivaad: why WebClientConfig uses @Bean with named beans

```java
// config/WebClientConfig.java
@Configuration
public class WebClientConfig {

    @Bean(name = "eCourtWebClient")
    public WebClient eCourtWebClient() {
        // eCourts-specific timeouts, User-Agent spoofing, Referer header
        return WebClient.builder()
                .baseUrl("https://services.ecourts.gov.in")
                ...
                .build();
    }

    @Bean(name = "claudeWebClient")
    public WebClient claudeWebClient() {
        // Claude API key header, anthropic-version header, JSON content type
        return WebClient.builder()
                .baseUrl(claudeBaseUrl)
                ...
                .build();
    }
}
```

Two `WebClient` beans of the same type. Without `name`, Spring would not know which one to inject where. The named beans are referenced by qualifier elsewhere:

```java
// In a client class that uses these beans:
@Service
public class ECourtWebClient {

    private final WebClient webClient;

    public ECourtWebClient(@Qualifier("eCourtWebClient") WebClient webClient) {
        this.webClient = webClient;
    }
}
```

### VaadVivaad: SecurityConfig bean factory chain

```java
// config/SecurityConfig.java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsServiceImpl userDetailsService;

    // Constructor injection — SecurityConfig itself is a Spring bean
    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          UserDetailsServiceImpl userDetailsService) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // HttpSecurity is injected by Spring — not something you construct
        http.csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/actuator/health", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());  // calls @Bean method — returns singleton
        return provider;
    }
}
```

`SecurityConfig` itself uses constructor injection (`JwtAuthFilter`, `UserDetailsServiceImpl`). Its `@Bean` methods build objects from third-party Spring Security classes. The `PasswordEncoder` is created by `passwordEncoder()` and reused in both `authenticationProvider()` (through the `@Bean` call which returns the singleton) and injected into `AuthService`.

---

## 8. Bean Scope

### Singleton (default)

```java
// Default — no annotation needed, this is always the default
@Service  // implicitly @Scope("singleton")
public class CaseLookupService { ... }
```

One instance per ApplicationContext. When two different classes inject `CaseLookupService`, they get the exact same object reference. This is why you must NOT store request-specific state in instance variables of service classes.

The singleton scope is the default because:
- It is the most memory-efficient.
- Spring-managed beans are almost always stateless services — there is no reason to create new instances.
- Creating a new `CaseLookupService` for every request would mean creating new `CourtCaseRepository` and `HearingRepository` references 1000 times per second under load.

**Thread safety implication:** since one instance serves all threads, instance variables shared across threads need synchronization. The solution is: do not use instance variables for request state. Use method-local variables only. VaadVivaad services correctly store request data in local variables, not fields.

### Prototype

```java
@Component
@Scope("prototype")
public class ReportBuilder {
    // New instance every time someone injects or requests this bean
    private List<String> lines = new ArrayList<>(); // state is safe here
}
```

New instance every time the bean is requested. Used when the bean carries state that must not be shared between callers. Rarely needed for service-layer classes.

**Trap:** injecting a `prototype` bean into a `singleton` bean creates one prototype instance (at singleton construction time) and holds it forever — effectively making it a singleton. To get a new prototype on every use, you must inject the `ApplicationContext` and call `getBean()`, or use a `@Lookup` method.

### Request and Session (web scopes)

```java
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestContext {
    private String traceId = UUID.randomUUID().toString();
}
```

`request` scope: one instance per HTTP request. Destroyed when the request completes. Useful for per-request state like correlation IDs.

`session` scope: one instance per HTTP session. VaadVivaad is stateless (JWT auth, `SessionCreationPolicy.STATELESS`), so session-scoped beans are not applicable here.

The `proxyMode = ScopedProxyMode.TARGET_CLASS` is required when injecting a short-lived scoped bean into a longer-lived singleton — Spring creates a proxy that delegates to the correct instance.

---

## 9. Auto-configuration

### How it works

Spring Boot auto-configuration is a collection of `@Configuration` classes in the `spring-boot-autoconfigure` jar. They use `@Conditional` annotations to decide whether to apply.

```
Your pom.xml includes spring-boot-starter-data-jpa
    |
    v
Pulls in: postgresql driver, Hibernate, Spring Data JPA, HikariCP
    |
    v
Spring Boot reads: META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    |
    v
Finds: DataSourceAutoConfiguration
    |
    v
Checks: @ConditionalOnClass(DataSource.class)       — true (driver is on classpath)
        @ConditionalOnMissingBean(DataSource.class)  — true (you have not defined one)
    |
    v
Creates: HikariDataSource from spring.datasource.url, username, password in application.yml
```

In `application-dev.yml`, VaadVivaad provides:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/vaadvivaad
    username: vaadvivaad
    password: vaadvivaad123
    driver-class-name: org.postgresql.Driver
```

Spring Boot's `DataSourceAutoConfiguration` reads these values and creates a `HikariDataSource` bean. You never wrote `new HikariDataSource(...)` anywhere.

Similarly:
- `spring.rabbitmq.*` → `RabbitAutoConfiguration` creates a `ConnectionFactory`
- `spring.data.redis.*` → `RedisAutoConfiguration` creates a `RedisConnectionFactory`
- Spring Security on classpath → `SecurityAutoConfiguration` creates a default filter chain (which VaadVivaad overrides with its own `SecurityFilterChain` bean)

### Overriding auto-configuration

The `@ConditionalOnMissingBean` pattern is how you override. VaadVivaad's `RedisConfig` overrides the default `CacheManager`:

```java
// RedisConfig.java defines:
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) { ... }

// This prevents RedisCacheManagerBuilderCustomizerAutoConfiguration from creating its own
// because that auto-config has @ConditionalOnMissingBean(CacheManager.class)
```

You provided a `CacheManager` bean, so Spring Boot's auto-configured one is never created.

### Seeing what was configured

```bash
# Run with debug flag to see auto-configuration report
java -jar vaadvivaad.jar --debug

# Output includes:
# Positive matches (conditions met, auto-config applied):
#   DataSourceAutoConfiguration matched:
#     - @ConditionalOnClass found required classes 'javax.sql.DataSource'
#
# Negative matches (conditions not met, auto-config skipped):
#   MongoAutoConfiguration:
#     - @ConditionalOnClass did not find required type 'com.mongodb.MongoClient'
```

---

## 10. @ConfigurationProperties

### The problem with @Value

```java
// Fragile — repeated everywhere, no type safety, typos discovered at runtime
@Service
public class JwtService {

    @Value("${application.security.jwt.secret-key}")
    private String secretKey;

    @Value("${application.security.jwt.expiration}")
    private long jwtExpiration;
}
```

If you have five classes all needing JWT config, you repeat these `@Value` strings in all five. Rename the property in `application.yml` and you must hunt down every `@Value` usage.

### The @ConfigurationProperties solution

```java
// A typed, bound configuration class — what VaadVivaad could use
@ConfigurationProperties(prefix = "application.security.jwt")
public record JwtProperties(
    String secretKey,
    long expiration
) {}

// Enable it — either in @SpringBootApplication class or in a @Configuration
@EnableConfigurationProperties(JwtProperties.class)
```

```yaml
# application.yml — same structure as before
application:
  security:
    jwt:
      secret-key: 404E635266556A586E32...
      expiration: 86400000
```

```java
// Usage — injected like any other bean
@Service
public class JwtService {

    private final JwtProperties jwtProperties;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateToken(UserDetails userDetails) {
        return Jwts.builder()
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.expiration()))
                .signWith(getSigningKey(jwtProperties.secretKey()))
                .compact();
    }
}
```

### Benefits

- **Type safety.** `expiration` is a `long`, not a string that you parse somewhere.
- **IDE support.** Spring generates `spring-configuration-metadata.json`, giving you autocomplete in `application.yml`.
- **Validation.** Add `@Validated` and `@NotNull`/`@Min` annotations on the properties record — Spring validates on startup.
- **Grouping.** All JWT config in one place. Rename once in the record.
- **Testability.** Construct `JwtProperties("secret", 86400000L)` in tests, no YAML needed.

---

## 11. @Profile — Dev vs Prod Config

### The problem

You want different behavior in development (verbose logging, local PostgreSQL, no real API keys) versus production (RDS, real credentials, minimal logging, optimized settings).

### How Spring profiles work

```yaml
# application.yml — base config, active in ALL profiles
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # never auto-migrate in any environment

# application-dev.yml — overrides for dev profile
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/vaadvivaad
  jpa:
    show-sql: true  # verbose SQL only in dev
logging:
  level:
    com.vaadvivaad: DEBUG

# application-prod.yml — overrides for prod profile
spring:
  datasource:
    url: ${DATABASE_URL}  # from environment variable
  jpa:
    show-sql: false
logging:
  level:
    com.vaadvivaad: WARN
```

Activate with: `--spring.profiles.active=dev` or env var `SPRING_PROFILES_ACTIVE=prod`.

### @Profile on beans

You can conditionally create beans based on active profile:

```java
// Only create this bean in dev — a stub that does not send real emails
@Component
@Profile("dev")
public class StubEmailSender implements EmailSender {
    @Override
    public void send(String to, String subject, String body) {
        log.info("DEV STUB — would have sent email to: {} | Subject: {}", to, subject);
    }
}

// Only create this bean in prod — actually sends emails via AWS SES
@Component
@Profile("prod")
public class AwsSesEmailSender implements EmailSender {
    @Override
    public void send(String to, String subject, String body) {
        // real SES call
    }
}
```

The interface `EmailSender` is injected everywhere. In dev, the stub is injected. In prod, AWS SES is injected. No `if (isDev)` in business logic anywhere.

### @Profile with @Configuration

```java
// Only load this entire config class in prod
@Configuration
@Profile("prod")
public class ProdSecurityConfig {

    @Bean
    public SecurityFilterChain strictFilterChain(HttpSecurity http) throws Exception {
        // prod-only: rate limiting, HTTPS enforcement, stricter CORS
        ...
    }
}
```

---

## 12. Bean Lifecycle

The full lifecycle of a Spring singleton bean:

```
ApplicationContext starts
        |
        v
1. INSTANTIATION
   - Spring calls the constructor (with injected dependencies)
   - For @Bean methods: Spring calls the factory method
   
        |
        v
2. DEPENDENCY INJECTION
   - @Autowired fields populated (if any)
   - @Autowired setters called (if any)
   - Constructor injection already happened in step 1
   
        |
        v
3. @PostConstruct
   - Any method annotated with @PostConstruct runs
   - Dependencies are guaranteed to be injected at this point
   - Use for: validation, cache warming, establishing connections
   
        |
        v
4. BEAN IS READY AND IN USE
   - Responds to method calls
   - Lives in ApplicationContext as a singleton
   - Handles concurrent requests
   
        |
        v
ApplicationContext is shut down (Ctrl+C, SIGTERM)
        |
        v
5. @PreDestroy
   - Any method annotated with @PreDestroy runs
   - Use for: releasing resources, closing connections, flushing caches
   
        |
        v
6. BEAN IS DESTROYED
```

### @PostConstruct example

```java
@Service
public class CaseIndexService {

    private final CourtCaseRepository repository;
    private Map<String, UUID> cnrIndex;

    public CaseIndexService(CourtCaseRepository repository) {
        this.repository = repository;
        // CANNOT use repository here in the constructor for initialization —
        // it is injected, but the transaction infrastructure may not be ready.
        // Use @PostConstruct for this.
    }

    @PostConstruct
    public void buildIndex() {
        // repository is guaranteed to be fully initialized here.
        // Spring's transaction support is also ready.
        this.cnrIndex = repository.findAll()
                .stream()
                .collect(Collectors.toMap(CourtCase::getCnrNumber, CourtCase::getId));
        log.info("CNR index built with {} entries", cnrIndex.size());
    }

    @PreDestroy
    public void clearIndex() {
        this.cnrIndex.clear();
        log.info("CNR index cleared on shutdown");
    }
}
```

**Why not just do this in the constructor?**

1. The repository is injected via constructor, so technically it is available. But `@Transactional` is implemented via AOP proxy. The proxy is not set up during construction — only after the `BeanPostProcessor` runs. If `buildIndex()` starts a transaction inside the constructor, the transaction interceptor is not yet wrapping the call.

2. If `buildIndex()` throws, you want a clear error at startup. Inside a constructor, the error stack trace is harder to diagnose because it appears as a bean creation failure.

3. `@PostConstruct` is the standard contract — other developers immediately understand its intent.

---

## 13. The Self-invocation Trap

This is one of the most important senior-level topics. It causes bugs that are invisible in testing and only appear in production.

### How Spring AOP proxies work

When you annotate a bean with `@Transactional` or `@Cacheable`, Spring does not modify your class. Instead, it creates a **proxy** — a subclass (via CGLIB) that wraps your class. The proxy intercepts method calls and applies the behavior (open transaction, check cache).

```
Caller → [Spring Proxy] → [Your actual object]
             |
             Opens transaction / checks cache
             Calls your method
             Commits transaction / stores in cache
```

Every injected reference points to the proxy, not to your actual object. So every external call goes through the proxy.

### The trap

```java
@Service
public class CaseLookupService {

    @Transactional(readOnly = true)
    @Cacheable(value = "cases", key = "#cnrNumber")
    public CaseResponse lookupByCnr(String cnrNumber) {
        // hits cache, wrapped in transaction
        ...
    }

    @Transactional
    @CacheEvict(value = "cases", allEntries = true)
    public CaseResponse createCase(CreateCaseRequest request) {
        // evicts cache, opens transaction
        ...
        // TRAP: calling lookupByCnr from here BYPASSES the proxy
        return this.lookupByCnr(request.getCnrNumber()); // this = actual object, not proxy
    }
}
```

When `createCase()` calls `this.lookupByCnr(...)`, `this` refers to the actual `CaseLookupService` object — not the proxy. The call goes directly to the method without passing through Spring's interceptors. Result:

- `@Cacheable` on `lookupByCnr` is NOT applied — the cache is never checked.
- If `lookupByCnr` had a different `@Transactional` setting (e.g., `REQUIRES_NEW`), that setting is silently ignored — it runs inside `createCase`'s transaction.

### The same trap with @Cacheable specifically

```java
@Service
public class CaseLookupService {

    @Cacheable(value = "cases", key = "#cnrNumber")
    public CaseResponse lookupByCnr(String cnrNumber) { ... }

    public void refreshAll(List<String> cnrNumbers) {
        for (String cnr : cnrNumbers) {
            // SELF-INVOCATION — @Cacheable is bypassed every single time
            // result is never cached, database is hit on every call
            this.lookupByCnr(cnr);
        }
    }
}
```

### Solutions

**Option 1: Move the called method to a different bean (cleanest)**

```java
@Service
public class CaseReadService {

    @Cacheable(value = "cases", key = "#cnrNumber")
    public CaseResponse lookupByCnr(String cnrNumber) { ... }
}

@Service
public class CaseWriteService {

    private final CaseReadService caseReadService; // injected proxy

    public CaseResponse createCase(CreateCaseRequest request) {
        // ... create ...
        return caseReadService.lookupByCnr(request.getCnrNumber()); // goes through proxy
    }
}
```

**Option 2: Self-inject (hacky but sometimes necessary)**

```java
@Service
public class CaseLookupService {

    @Autowired
    private CaseLookupService self; // Spring injects the PROXY reference

    public CaseResponse createCase(CreateCaseRequest request) {
        return self.lookupByCnr(request.getCnrNumber()); // self = proxy, works correctly
    }
}
```

This works but is a design smell. It signals the class has too many responsibilities.

**Option 3: Use ApplicationContext.getBean() (avoid)**

```java
CaseLookupService proxy = applicationContext.getBean(CaseLookupService.class);
proxy.lookupByCnr(cnrNumber); // goes through proxy
```

### Why this matters for VaadVivaad

`CaseLookupService.createCase()` calls `courtCaseRepository.save()` which is `@Transactional`. The `@CacheEvict` on `createCase` evicts the cache on exit. If internally you called `lookupByCnr()` (a `@Cacheable` method) via `this.`, you would re-populate a stale cache entry that was just evicted. The self-invocation trap can cause subtle, hard-to-reproduce cache consistency bugs.

---

## 14. Node → Java Comparison Table

| Concept | Node.js Equivalent | Spring/Java |
|---|---|---|
| IoC Container | Module system — `require()` returns singletons | `ApplicationContext` — holds all beans |
| Bean definition | `module.exports = new MyService()` | `@Service`, `@Component`, or `@Bean` method |
| Dependency injection | `const service = require('./service')` in the module that needs it | Constructor parameter — Spring injects automatically |
| Singleton by default | Node module cache — `require()` returns same object every time | `@Scope("singleton")` — same instance per context |
| Prototype scope | `function createService() { return new Service(); }` — new instance per call | `@Scope("prototype")` |
| Configuration class | `config.js` that exports objects | `@Configuration` class with `@Bean` methods |
| Environment variables / config | `process.env.*` or `dotenv` | `application.yml` + `@Value` / `@ConfigurationProperties` |
| Dev vs Prod config | `.env.development` / `.env.production` | `application-dev.yml` / `application-prod.yml` + `@Profile` |
| Auto-configuration | `express()` pre-configuring middleware defaults | `@EnableAutoConfiguration` + `@ConditionalOn*` |
| AOP proxy | Express middleware wrapping route handlers | CGLIB proxy wrapping beans (`@Transactional`, `@Cacheable`) |
| @PostConstruct | `async function init() {}` called after module load in an IIFE | `@PostConstruct` method runs after all dependencies injected |
| @PreDestroy | `process.on('SIGTERM', cleanup)` | `@PreDestroy` method runs before context shutdown |
| Self-invocation trap | Calling your own route handler internally bypasses middleware | Calling `this.method()` inside a bean bypasses the Spring proxy |
| `@Bean(name = "x")` | `module.exports = { ecourtClient: ..., claudeClient: ... }` | Named beans, injected with `@Qualifier("x")` |
| Bean scope: request | `res.locals` — per-request state | `@Scope("request")` with proxy mode |
| Component scan | No equivalent — you explicitly `require()` everything | `@ComponentScan` recursively finds all annotated classes |
| @Repository exception translation | Mongoose/Sequelize error handling wrappers | Spring wraps JPA exceptions in `DataAccessException` hierarchy |

---

## 15. Senior Interview Q&A — 10 Pairs

### Q1: What is the difference between IoC and DI? Developers often conflate them.

**Answer:**

IoC is the design principle: invert control of object creation from your code to a framework. DI is the *mechanism* Spring uses to implement IoC. IoC says "do not let your business classes create their own dependencies." DI says "pass dependencies into the class via constructor, setter, or field."

You could implement IoC without DI — for example, using a Service Locator pattern where classes look up their dependencies from a registry. DI is more testable because the class has no coupling to the registry.

In Spring, IoC is the container's responsibility, and DI is how the container delivers beans to each other.

### Q2: Why is constructor injection preferred over field injection? What are the concrete downsides of field injection?

**Answer:**

Field injection uses reflection to set private fields after construction. The concrete downsides:

1. Fields cannot be `final` — the object is mutable. Any method could theoretically re-assign a dependency reference.
2. Testing without Spring requires either spinning up a Spring context or using `ReflectionTestUtils.setField()` — a reflection hack that breaks when you rename the field.
3. The class's dependencies are invisible until you read every field annotation. With constructor injection, the constructor signature is a complete, explicit contract.
4. Circular dependencies are hidden. With constructor injection, Spring detects circular dependencies at startup and fails with a clear error. With field injection, Spring may silently inject partially-constructed objects.
5. IntelliJ IDEA, Spring's own documentation, and Spring's official style guide all recommend against it.

### Q3: Explain what @SpringBootApplication does. What are its three composed annotations and what does each one actually do?

**Answer:**

`@SpringBootApplication` composes:

- `@Configuration` — marks the class as a source of bean definitions. Spring processes it with CGLIB to enable the singleton guarantee for `@Bean` method calls.
- `@EnableAutoConfiguration` — triggers Spring Boot's auto-configuration mechanism. It reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, finds all auto-configuration classes, evaluates their `@Conditional` conditions, and applies the ones that match. Having the PostgreSQL driver on the classpath and `spring.datasource.*` in `application.yml` causes `DataSourceAutoConfiguration` to create a `HikariDataSource` without you writing any code.
- `@ComponentScan` — recursively scans the package of the annotated class and all sub-packages. Every class annotated with `@Component`, `@Service`, `@Repository`, `@Controller`, or `@RestController` is registered as a bean.

### Q4: What is a bean scope and what are the implications of Singleton scope for thread safety?

**Answer:**

Bean scope controls how many instances of a bean exist and how long they live. The default is Singleton — one instance per ApplicationContext, shared by all injection points.

Singleton scope means your bean methods will be called by multiple threads simultaneously. Instance variables of a singleton bean that hold request-specific state will be corrupted under concurrent access. The solution is to use only method-local variables for request state — never store per-request data in `this.someField` of a singleton service.

VaadVivaad services like `CaseLookupService` and `AuthService` correctly store all per-operation state in method-local variables. The only instance variables are the injected dependencies themselves, which are themselves stateless singletons. This is the correct pattern.

### Q5: What is the self-invocation trap with @Transactional and @Cacheable?

**Answer:**

Spring implements `@Transactional` and `@Cacheable` using AOP proxies. When you inject a `CaseLookupService`, you get a Spring proxy, not the actual object. When an external class calls a method on that proxy, the proxy intercepts the call and applies transaction/cache behavior before delegating to the real object.

When a method inside `CaseLookupService` calls `this.lookupByCnr(cnr)`, the `this` reference points to the real object, not the proxy. The call bypasses the proxy entirely. `@Cacheable` is not checked, `@Transactional` settings like `REQUIRES_NEW` are ignored.

The fix is to extract the called method into a separate bean (so external injection gives you the proxy), or in rare cases to self-inject the bean and call through the injected reference (which is the proxy).

### Q6: When would you use @Bean in a @Configuration class instead of @Component on the class itself?

**Answer:**

Use `@Component` when it is your own class and Spring can discover it via component scan.

Use `@Bean` in a `@Configuration` class when:
- You need to configure a third-party class you cannot annotate. You cannot add `@Component` to `RabbitTemplate`, `WebClient`, or `CacheManager`.
- The construction requires complex initialization code — like VaadVivaad's `RedisConfig.cacheManager()` which configures Jackson serialization before building the cache manager.
- You need multiple beans of the same type — like VaadVivaad's two `WebClient` beans (`eCourtWebClient` and `claudeWebClient`), distinguished by name and qualifier.
- You need conditional logic: `@ConditionalOnProperty`, `@ConditionalOnMissingBean`.

### Q7: How does Spring Boot auto-configuration work, and how do you override it?

**Answer:**

Auto-configuration is a collection of `@Configuration` classes in `spring-boot-autoconfigure.jar`. Each class has `@Conditional` annotations — most commonly `@ConditionalOnClass` (is this library on the classpath?) and `@ConditionalOnMissingBean` (has the user defined their own bean?).

Spring Boot reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to discover all auto-configuration candidates, evaluates conditions, and applies the matching ones.

To override: define a `@Bean` of the same type. The auto-config class has `@ConditionalOnMissingBean(CacheManager.class)`, so VaadVivaad's `RedisConfig.cacheManager()` takes precedence and the auto-configured one is never created.

Run with `--debug` flag to get a complete auto-configuration report showing which conditions matched and which did not.

### Q8: Explain the complete bean lifecycle, including @PostConstruct and @PreDestroy. When would you use each?

**Answer:**

Lifecycle in order:
1. Constructor called (dependencies injected via constructor).
2. Any remaining `@Autowired` fields/setters populated.
3. `@PostConstruct` method runs.
4. Bean is in use, handling method calls.
5. `@PreDestroy` method runs when context is shutting down.
6. Bean is eligible for garbage collection.

Use `@PostConstruct` when you need to run initialization logic that depends on injected beans. You cannot do this in the constructor because AOP proxies (and thus `@Transactional`) are not yet set up during construction. Common uses: warming a cache from the database, establishing a connection, registering listeners.

Use `@PreDestroy` to release resources cleanly: closing connections, flushing caches, canceling scheduled tasks. This runs when the JVM receives SIGTERM (Kubernetes scale-down, application shutdown).

### Q9: What is @ConfigurationProperties and why is it better than @Value for groups of related config?

**Answer:**

`@Value("${some.property}")` is fine for a single property, but breaks down when you have a group of related properties. You repeat the prefix string in every `@Value` annotation, there is no type safety, typos are discovered at runtime, and IDE autocomplete does not work.

`@ConfigurationProperties(prefix = "some")` binds an entire YAML subtree to a typed Java class or record. Benefits: single source of truth for the prefix, type coercion happens automatically, `@Validated` on the class enables `@NotNull`/`@Min` checks at startup, IDE generates autocomplete metadata. In tests, you construct the properties object directly without parsing YAML.

### Q10: Two classes depend on each other — A depends on B and B depends on A. Both use constructor injection. What happens?

**Answer:**

Spring detects the circular dependency at startup and throws `BeanCurrentlyInCreationException`. The application refuses to start with a clear error message listing the cycle.

This is actually a feature of constructor injection — it forces you to fix the design before the app starts. The cycle is a design smell: it usually means the two classes have overlapping responsibilities that should be extracted into a third class.

With field injection, Spring could silently inject a partially constructed bean into the other, leading to subtle `NullPointerException` bugs at runtime when the uninitialized field is accessed.

Solutions to a real circular dependency:
1. Extract shared logic into a third class that neither A nor B depends on circularly.
2. Use `@Lazy` on one of the constructor parameters — Spring creates a lazy proxy, breaking the cycle at the cost of deferring initialization.
3. Convert one dependency to setter injection — setter injection circular dependencies are resolved because the object is constructed first, then wired.

The right answer in an interview is: refactor to eliminate the cycle. `@Lazy` is a workaround, not a fix.

---

## 16. Senior Differentiators

A mid-level developer knows the annotations. A senior developer understands the mechanism underneath. Here is what separates the answers.

### On IoC

**Mid-level:** "IoC means Spring creates objects for you."

**Senior:** "IoC is a design principle — inverting the direction of dependency. In traditional code, business logic controls infrastructure. With IoC, the container controls object creation and lifecycle, and business logic declares its dependencies. This makes business logic infrastructure-agnostic and testable in isolation."

### On DI injection styles

**Mid-level:** "I use @Autowired on fields, it is simpler."

**Senior:** "Constructor injection is the only style I use for required dependencies, and I enforce it with `final` fields. The reasons are immutability, explicitness, testability without a Spring context, and early failure detection for circular dependencies. `@Autowired` on fields breaks all four of these properties. The Spring team itself recommends against field injection."

### On @Bean vs @Component

**Mid-level:** "I use @Bean for things like WebClient configuration."

**Senior:** "I use `@Component` when I own the class and it has no complex construction logic. I use `@Bean` for three cases: third-party classes I cannot annotate, complex initialization that requires code (like VaadVivaad's `CacheManager` which needs Jackson module registration and type activation), and multiple beans of the same type that need to be distinguished by qualifier. The key insight is that `@Bean` methods in `@Configuration` classes go through a CGLIB proxy, so calling one `@Bean` method from another returns the singleton — it does not create a new instance."

### On the self-invocation trap

**Mid-level:** "I know @Transactional does not work when you call a method from the same class."

**Senior:** "Spring AOP is proxy-based. Every injected bean reference is a proxy. `this` inside a class is the actual object, not the proxy. So `this.method()` bypasses the proxy entirely. This is not just a `@Transactional` issue — it affects everything implemented via AOP: `@Cacheable`, `@Async`, `@Retry`, `@Secured`, custom aspects. The correct fix is to extract the method to a separate bean. Self-injection (`@Autowired private MyService self`) works but signals a design problem — the class has too many responsibilities."

### On auto-configuration

**Mid-level:** "Spring Boot auto-configures things based on what's on the classpath."

**Senior:** "Auto-configuration is a set of `@Configuration` classes guarded by `@Conditional` annotations. The two most important are `@ConditionalOnClass` and `@ConditionalOnMissingBean`. The `ConditionalOnMissingBean` pattern is what makes auto-configuration overridable — define your own bean and the auto-config steps aside. You can see exactly what was configured and why by running with `--debug`. You can exclude specific auto-configuration classes with `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` if you need to."

### On bean lifecycle and @PostConstruct

**Mid-level:** "@PostConstruct runs after the bean is created."

**Senior:** "@PostConstruct runs after the bean is constructed and all its dependencies are injected, but before it is put into use. Critically, by this point, BeanPostProcessors have run, which means AOP proxies are set up. So if `@PostConstruct` method calls `this.someMethod()`, that is still self-invocation and still bypasses the proxy. But if `@PostConstruct` method calls an injected dependency's method, the proxy is in place. The practical implication: @PostConstruct is safe for initialization that calls injected dependencies, but not for initialization that relies on your own class's AOP-decorated methods."

### On thread safety and singleton scope

**Mid-level:** "Singleton beans should be thread-safe."

**Senior:** "Singleton scope means one instance for the entire ApplicationContext. 'Thread-safe' for a Spring service means no shared mutable state — all per-request data lives in method-local variables. The injected dependencies are themselves singletons, so they carry no per-request state by definition. The common mistake is storing user-specific data in an instance variable of a `@Service` class thinking each user gets their own service instance — they do not. The session of user A could overwrite the instance variable read by user B's thread. This is the most common concurrency bug I see in Spring codebases."

### On ApplicationContext startup sequence

**Mid-level:** "Spring scans packages and creates beans."

**Senior:** "The startup sequence has six distinct phases: bootstrap (context creation, environment loading), component scanning (building BeanDefinitions — blueprints, not instances yet), auto-configuration (evaluating conditionals), instantiation (topological sort by dependency graph, then calling constructors), post-processing (BeanPostProcessors create AOP proxies, @PostConstruct runs), and finally the context-ready event. The distinction between BeanDefinition and bean instance matters — you can programmatically add BeanDefinitions before instantiation starts, which is how frameworks like Spring Data generate repository implementations at startup without you writing any code."

---

*VaadVivaad is Spring Boot 3.4.4 / Java 21. All code examples above are drawn from actual project files unless explicitly labeled as illustrative examples.*
