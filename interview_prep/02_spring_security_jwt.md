# 02 — Spring Security & JWT Authentication
### VaadVivaad Interview Prep | Spring Boot 3.4.4 / Java 21

---

## Table of Contents

1. [Why Spring Security Feels Overwhelming (And Why It Shouldn't)](#1-why-spring-security-feels-overwhelming)
2. [SecurityFilterChain — The Modern Way](#2-securityfilterchain)
3. [OncePerRequestFilter — Your Custom Middleware](#3-oncepereqeustfilter)
4. [The Full JWT Flow in VaadVivaad](#4-the-full-jwt-flow)
5. [JwtService Internals — JJWT Deep Dive](#5-jwtservice-internals)
6. [UserDetails and UserDetailsService — The Spring Contract](#6-userdetails-and-userdetailsservice)
7. [SecurityContextHolder — The ThreadLocal Request Vault](#7-securitycontextholder)
8. [The Authentication Object](#8-the-authentication-object)
9. [GrantedAuthority and Roles](#9-grantedauthority-and-roles)
10. [CORS — Why It Lives in SecurityConfig](#10-cors-configuration)
11. [Public vs Protected Routes](#11-public-vs-protected-routes)
12. [Password Hashing with BCrypt](#12-password-hashing)
13. [Timing-Safe Login — The Enumeration Attack](#13-timing-safe-login)
14. [Token Expiry and the Refresh Token Gap](#14-token-expiry-and-refresh)
15. [Common Spring Security Mistakes](#15-common-mistakes)
16. [Node.js → Java Concept Map](#16-nodejs--java-concept-map)
17. [Senior Interview Q&A](#17-senior-interview-qa)
18. [Senior Differentiators](#18-senior-differentiators)

---

## 1. Why Spring Security Feels Overwhelming

Most developers approach Spring Security and think it's some kind of annotation magic or configuration soup. It's not. At its core it is exactly the same idea you already know from Node.js:

**Node.js mental model:**
```javascript
// Express middleware chain — every request passes through these in order
app.use(cors());
app.use(express.json());
app.use(rateLimiter);
app.use(authenticate);   // ← your JWT middleware lives here
app.use('/api', routes);
```

**Spring Security mental model:**
```
Request → [Filter1] → [Filter2] → ... → [JwtAuthFilter] → [UsernamePasswordAuthFilter] → Controller
```

Spring Security is a **chain of servlet filters** that wraps your application. Every HTTP request passes through this chain before reaching your controllers. Your job is to:

1. Configure which filters run and in what order
2. Tell the chain which routes are public and which require authentication
3. Plug in your custom JWT filter at the right position

The old way to do this was extending `WebSecurityConfigurerAdapter` and overriding methods. Spring Security 6 (Spring Boot 3+) deprecated that entirely. Now you declare a single `SecurityFilterChain` bean, which is more explicit and testable. VaadVivaad uses this modern approach.

**The key insight:** Spring Security does not know what "authenticated" means for your app. You teach it. You give it your `UserDetailsService` (how to load a user), your `PasswordEncoder` (how to compare passwords), and your custom filter (how to read JWT tokens). Spring Security wires the plumbing; you supply the logic.

---

## 2. SecurityFilterChain

`SecurityFilterChain` is a Spring bean that defines the entire security policy for your application. Think of it as the master configuration object that answers: "For any incoming request, what rules apply?"

**VaadVivaad's SecurityConfig.java:**
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // enables @PreAuthorize on methods
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/auth/**",
                    "/api/test/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

**Breaking down each method call:**

### `csrf(AbstractHttpConfigurer::disable)`

CSRF (Cross-Site Request Forgery) protection works by issuing a secret token that must be present in every state-changing request. It exists to protect **session-based** web apps — a malicious website cannot forge a request that includes the CSRF token stored in your browser's session cookie.

With JWT, you are **stateless**: no session, no session cookie. The browser sends the JWT in the `Authorization` header, not in a cookie. A CSRF attack cannot steal a header the same way it can ride a cookie. So disabling CSRF for a JWT REST API is correct, not a shortcut.

Node equivalent:
```javascript
// You never set up CSRF middleware in a pure JWT API for the same reason
// app.use(csrf()); // ← only for session-based apps
```

### `sessionManagement(STATELESS)`

This tells Spring Security: "Never create an HTTP session. Never store authentication state in a session. Treat every request as if it arrived from a stranger." This is the REST API contract. Without this, Spring might create sessions even though you never asked for them.

### `authorizeHttpRequests()`

This is the URL access control layer. It maps URL patterns to access decisions. The order matters — first match wins:

```
/api/auth/**    → permitAll()   (login, register — no token needed)
/api/test/**    → permitAll()   (health checks, test endpoints)
/actuator/health → permitAll()  (Docker/k8s health probes)
/v3/api-docs/** → permitAll()   (Swagger — devs need this unauthenticated)
/swagger-ui/**  → permitAll()   (Swagger UI)
anything else   → authenticated() (must have a valid JWT)
```

Node equivalent:
```javascript
// Express route-level auth
router.post('/auth/login', loginHandler);         // public
router.post('/auth/register', registerHandler);   // public
router.use(authenticate);                          // everything below needs token
router.get('/cases', getCases);                   // protected
```

### `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`

This inserts your `JwtAuthFilter` into the filter chain, positioned **before** Spring's own `UsernamePasswordAuthenticationFilter`. Why before? Because you want to authenticate the user via JWT before Spring's default filter tries to do username/password form-based auth (which you are not using for API requests). If your filter succeeds in establishing identity, Spring's filter has nothing left to do.

### `authenticationProvider()`

```java
@Bean
public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
}
```

`DaoAuthenticationProvider` is the bridge between Spring Security's `AuthenticationManager` and your database. When `AuthenticationManager.authenticate()` is called (during login), it delegates to this provider, which:
1. Calls `userDetailsService.loadUserByUsername(email)` to fetch user from DB
2. Uses `passwordEncoder.matches(rawPassword, storedHash)` to verify the password
3. Returns a populated `Authentication` object if successful, throws `BadCredentialsException` if not

---

## 3. OncePerRequestFilter

`OncePerRequestFilter` is a base class that guarantees your filter runs **exactly once per request**, even in complex servlet dispatch scenarios (like error forwarding which can re-trigger the filter chain). You extend it instead of implementing `Filter` directly.

The contract is simple: override `doFilterInternal()`. Inside, you do your logic, then call `filterChain.doFilter(request, response)` to pass the request to the next filter.

**VaadVivaad's JwtAuthFilter:**
```java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Try to read the Authorization header
        final String authHeader = request.getHeader("Authorization");

        // 2. No token? Pass through — let the auth rules handle it
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;   // ← early return, crucial
        }

        // 3-10. Token present — validate and set authentication
        // ... (detailed in next section)

        // 11. Always continue the chain
        filterChain.doFilter(request, response);
    }
}
```

**Two critical design choices visible here:**

**Choice 1 — The filter is not a gatekeeper, it's a decorator.** If there is no token, the filter does not return 401. It just passes the request through unchanged. The 401 comes later from the `authorizeHttpRequests` rules if the route requires authentication. This separation of concerns is important: the filter handles "who are you?", the URL rules handle "are you allowed here?".

**Choice 2 — Always call `filterChain.doFilter()` at the end.** Whether authentication succeeded or failed, you still continue the chain. If you don't call this, the request stops dead and the client gets no response. The exception is the early return on line 2 — that still calls `filterChain.doFilter()` before returning.

Node equivalent:
```javascript
function authenticate(req, res, next) {
    const authHeader = req.headers['authorization'];

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return next(); // pass through, route handler decides if it needs auth
    }

    const token = authHeader.substring(7);
    try {
        const decoded = jwt.verify(token, SECRET);
        req.user = decoded;         // ← Node: attach to request
        next();                      // ← Spring: SecurityContextHolder.setAuthentication()
    } catch (err) {
        next(); // also pass through — the route will require auth if needed
    }
}
```

---

## 4. The Full JWT Flow in VaadVivaad

This is the story of a single authenticated request from wire to controller.

### 4a. Login (Token Issuance)

```
POST /api/auth/login
Body: { "email": "user@example.com", "password": "secret123" }
```

1. `AuthController.login()` receives the request, delegates to `AuthService.login()`
2. `AuthService.login()` calls `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))`
3. `AuthenticationManager` delegates to `DaoAuthenticationProvider`
4. `DaoAuthenticationProvider` calls `UserDetailsServiceImpl.loadUserByUsername(email)` → DB query
5. `DaoAuthenticationProvider` calls `passwordEncoder.matches(rawPassword, storedHash)` → BCrypt verify
6. If mismatch → `BadCredentialsException` → Spring returns 401
7. If match → `AuthService` fetches the `User` entity, builds a `UserDetails` object
8. `JwtService.generateToken(userDetails)` creates and signs the JWT
9. Response returns `{ token, email, role, message }`

### 4b. Subsequent Authenticated Requests

```
GET /api/cases
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

**Step 1 — JwtAuthFilter intercepts**
```java
final String authHeader = request.getHeader("Authorization");
// authHeader = "Bearer eyJhbGciOiJIUzI1NiJ9..."
```

**Step 2 — Strip the "Bearer " prefix**
```java
final String jwt = authHeader.substring(7);
// jwt = "eyJhbGciOiJIUzI1NiJ9..."
```

**Step 3 — Extract username from token (without full validation yet)**
```java
final String userEmail = jwtService.extractUsername(jwt);
// Parses the JWT, extracts the "sub" claim = email
// If JWT is malformed, this throws and request fails
```

**Step 4 — Check if already authenticated**
```java
if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
    // Only proceed if we have a username AND this request isn't already authenticated
    // Prevents double-processing
```

**Step 5 — Load user from database**
```java
UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
// Hits the DB: SELECT * FROM users WHERE email = ?
// Returns UserDetails with email, hashed password, and roles
```

**Step 6 — Full token validation**
```java
if (jwtService.isTokenValid(jwt, userDetails)) {
    // Checks: does the token's "sub" match the loaded user's email?
    // AND: is the token not expired?
```

**Step 7 — Build the Authentication object**
```java
UsernamePasswordAuthenticationToken authToken =
        new UsernamePasswordAuthenticationToken(
                userDetails,            // principal (who the user is)
                null,                   // credentials (null — already verified)
                userDetails.getAuthorities()  // roles: [ROLE_USER]
        );
```

**Step 8 — Attach request metadata**
```java
authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
// Attaches IP address, session ID (if any) for audit/logging purposes
```

**Step 9 — Store in SecurityContextHolder**
```java
SecurityContextHolder.getContext().setAuthentication(authToken);
// The request is now "authenticated" — Spring Security trusts it
```

**Step 10 — Continue the filter chain**
```java
filterChain.doFilter(request, response);
// Request proceeds to controller
// Controller can now call SecurityContextHolder.getContext().getAuthentication()
// to know who made the request
```

**Step 11 — Controller executes and SecurityContext is cleared**

After the response is sent, Spring Security automatically clears the `SecurityContextHolder` to prevent leaking authentication state across requests (since we're using `ThreadLocal` storage — see section 7).

---

## 5. JwtService Internals

JJWT (Java JWT) is to Java what `jsonwebtoken` is to Node. VaadVivaad uses JJWT's modern API (version 0.12+).

**VaadVivaad's JwtService.java:**

### The Signing Key

```java
@Value("${application.security.jwt.secret-key}")
private String secretKey;  // Base64-encoded secret from application.properties

private SecretKey getSigningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(keyBytes);
}
```

The secret key is stored in config as a Base64 string (safer than raw bytes in properties files). `Keys.hmacShaKeyFor()` creates a `SecretKey` object for HMAC-SHA algorithms. JJWT automatically picks the strongest algorithm the key length supports — for 256-bit keys it uses **HMAC-SHA256** (HS256).

Node equivalent:
```javascript
const SECRET = process.env.JWT_SECRET; // plain string or Buffer
jwt.sign(payload, SECRET, { algorithm: 'HS256' });
```

### Token Generation

```java
public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
    return Jwts.builder()
            .claims(extraClaims)                                          // custom claims
            .subject(userDetails.getUsername())                           // "sub" = email
            .issuedAt(new Date(System.currentTimeMillis()))               // "iat"
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration)) // "exp"
            .signWith(getSigningKey())                                    // signs with HS256
            .compact();                                                   // serialize to string
}
```

The resulting JWT has three parts: `header.payload.signature`
- **Header:** `{ "alg": "HS256" }`
- **Payload:** `{ "sub": "user@example.com", "iat": 1234567890, "exp": 1234654290 }`
- **Signature:** HMAC-SHA256(base64url(header) + "." + base64url(payload), secretKey)

Node equivalent:
```javascript
const token = jwt.sign(
    { sub: email, iat: Math.floor(Date.now() / 1000) },
    SECRET,
    { expiresIn: '24h', algorithm: 'HS256' }
);
```

### Claims Extraction

```java
public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
}

private Claims extractAllClaims(String token) {
    return Jwts.parser()
            .verifyWith(getSigningKey())   // must provide key to verify signature
            .build()
            .parseSignedClaims(token)      // verifies signature + expiry, throws if invalid
            .getPayload();                 // returns the Claims map
}
```

`parseSignedClaims()` does three things atomically:
1. Verifies the **signature** (was this token created with our key?)
2. Checks **expiry** (has `exp` passed?)
3. Returns the **payload** if both pass

Notice the functional approach: `extractClaim(token, Claims::getSubject)` passes a method reference as a lambda. `Claims::getSubject` is equivalent to `claims -> claims.getSubject()`. This avoids repeating the parse logic for every different claim.

### Token Validation

```java
public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
}
```

Note that `extractUsername()` already calls `parseSignedClaims()` which validates signature and expiry. So `isTokenExpired()` is technically redundant here — if the token were expired, `extractUsername()` would have already thrown a `JwtException`. The explicit check is defensive programming and makes intent clear.

---

## 6. UserDetails and UserDetailsService

These are Spring Security's core contracts. Think of them as interfaces that Spring Security defines, and you implement.

### UserDetails — The Identity Contract

`UserDetails` is an interface representing "what Spring Security needs to know about a user." It has six methods:

| Method | Returns | Purpose |
|--------|---------|---------|
| `getUsername()` | String | The unique identifier (email in VaadVivaad) |
| `getPassword()` | String | The stored hashed password |
| `getAuthorities()` | Collection<GrantedAuthority> | Roles/permissions |
| `isAccountNonExpired()` | boolean | Account validity |
| `isAccountNonLocked()` | boolean | Account lock status |
| `isEnabled()` | boolean | Whether account is active |

**VaadVivaad uses Spring's built-in `User` class** (confusingly named the same as the entity) which implements `UserDetails`. Both `UserDetailsServiceImpl` and `AuthService` build it the same way:

```java
// In UserDetailsServiceImpl.loadUserByUsername():
return new org.springframework.security.core.userdetails.User(
        user.getEmail(),          // username (identifier)
        user.getPasswordHash(),   // hashed password
        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        // e.g., "ROLE_USER", "ROLE_LAWYER", "ROLE_ADMIN"
);
```

Notice the fully qualified class name `org.springframework.security.core.userdetails.User` — this is required because `com.vaadvivaad.user.entity.User` (the JPA entity) is also imported. This is a common naming collision in Spring apps.

**Alternative: implement UserDetails on the entity itself.** Many tutorials have `User.java` implement `UserDetails` directly. This is simpler but couples your domain model to Spring Security. VaadVivaad keeps them separate, which is cleaner for testing and future flexibility.

### UserDetailsService — The Loading Contract

```java
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(user -> new org.springframework.security.core.userdetails.User(
                        user.getEmail(),
                        user.getPasswordHash(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                ))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with email: " + email));
    }
}
```

Spring Security calls `loadUserByUsername()` in two scenarios:
1. During `DaoAuthenticationProvider.authenticate()` — to load the user for password comparison (login flow)
2. During `JwtAuthFilter` — explicitly called to load user for SecurityContext population (per-request flow)

The `@Transactional(readOnly = true)` annotation is important. It tells JPA to open a read-only transaction for this DB call, which some databases optimize for (no dirty checking, no flush). Since this is called on every authenticated request, it should be as fast as possible.

Node equivalent:
```javascript
// passport.js local strategy — the same concept
passport.use(new LocalStrategy(
    { usernameField: 'email' },
    async (email, password, done) => {
        const user = await User.findOne({ email });   // loadUserByUsername equivalent
        if (!user) return done(null, false);
        if (!bcrypt.compareSync(password, user.passwordHash)) return done(null, false);
        return done(null, user);
    }
));
```

---

## 7. SecurityContextHolder

`SecurityContextHolder` is Spring Security's **per-request identity store**. It uses `ThreadLocal` storage — a Java mechanism that attaches data to the current thread rather than to a global variable. Since each HTTP request is handled by one thread (in traditional servlet containers), this gives you per-request isolation.

**Writing to it (in JwtAuthFilter):**
```java
SecurityContextHolder.getContext().setAuthentication(authToken);
```

**Reading from it (in a controller or service):**
```java
// Getting the current user's email in a controller
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String currentUserEmail = auth.getName(); // delegates to principal.getUsername()

// Getting the full UserDetails
UserDetails userDetails = (UserDetails) auth.getPrincipal();

// Getting roles
Collection<? extends GrantedAuthority> roles = auth.getAuthorities();
```

**The lifecycle:**
1. Request arrives → Spring Security creates a new `SecurityContext`
2. `JwtAuthFilter` populates it with `setAuthentication()`
3. Controllers/services read from it during request processing
4. After response is sent → `SecurityContextHolder.clearContext()` is called automatically (by `SecurityContextPersistenceFilter` or `SecurityContextHolderFilter` in Spring Security 6)

**Why ThreadLocal?** It allows any layer of your application (controller, service, repository) to access the current user's identity without passing it explicitly through every method signature. It's "ambient" authentication context.

**Why it clears automatically:** Without this, thread pool reuse (which servlet containers do) would leak authentication from request A to request B when the thread is reused. Always clear after use.

Node equivalent:
```javascript
// Node doesn't have ThreadLocal — everything is on the request object
// You pass req.user explicitly or use async_hooks (complex)
req.user = decoded; // set in middleware
// then in handler:
const currentUser = req.user; // read from request
```

This is one area where Spring's approach is more "magical" than Node's. Spring's `SecurityContextHolder` works globally within a thread; Node's `req.user` is explicit per-request.

---

## 8. The Authentication Object

`UsernamePasswordAuthenticationToken` is Spring Security's concrete implementation of the `Authentication` interface. Despite its name suggesting username/password, it is used for **any** fully-authenticated user — the "UsernamePassword" part just reflects its origin.

**VaadVivaad creates it with three arguments:**

```java
UsernamePasswordAuthenticationToken authToken =
        new UsernamePasswordAuthenticationToken(
                userDetails,                    // principal
                null,                           // credentials
                userDetails.getAuthorities()    // authorities
        );
```

**Principal:** "Who is this user?" — In VaadVivaad this is the `UserDetails` object (Spring's `User` class with email, password hash, and roles). It can be anything; some apps store a custom `CurrentUser` DTO here.

**Credentials:** "What did they prove identity with?" — For JWT auth this is `null` because the proof was the token itself, which we have already validated. For username/password login, this would be the raw password temporarily. Spring Security clears credentials after auth to avoid keeping passwords in memory longer than needed.

**Authorities:** The collection of `GrantedAuthority` objects. This is what `@PreAuthorize`, `hasRole()`, and `authorizeHttpRequests()` inspect.

**Two-arg vs three-arg constructor — important difference:**
```java
// Two-arg: NOT authenticated (used to carry credentials TO the AuthenticationManager)
new UsernamePasswordAuthenticationToken(email, password)
// → isAuthenticated() returns false

// Three-arg: IS authenticated (used after successful verification)
new UsernamePasswordAuthenticationToken(principal, null, authorities)
// → isAuthenticated() returns true
```

VaadVivaad's `AuthService.login()` uses the two-arg version to pass credentials to `authenticationManager.authenticate()`. The filter uses the three-arg version after verifying the JWT.

---

## 9. GrantedAuthority and Roles

`GrantedAuthority` is the interface that represents a single permission or role. `SimpleGrantedAuthority` is its trivial implementation — just a string wrapper.

**The ROLE_ prefix convention:**

Spring Security distinguishes between:
- **Roles** — prefixed with `ROLE_`, used with `hasRole('USER')` (Spring strips the prefix when checking)
- **Authorities** — any string, used with `hasAuthority('READ_CASES')`

```java
// VaadVivaad stores roles with ROLE_ prefix:
new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
// For Role.USER    → "ROLE_USER"
// For Role.LAWYER  → "ROLE_LAWYER"
// For Role.ADMIN   → "ROLE_ADMIN"
```

**VaadVivaad's Role enum:**
```java
public enum Role {
    USER,    // Regular user — view their own cases
    LAWYER,  // Lawyer — manage assigned cases
    ADMIN    // Admin — full access
}
```

**Using roles in method security (enabled by `@EnableMethodSecurity`):**
```java
@GetMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<?> getAllUsers() { ... }

// hasRole('ADMIN') checks for authority "ROLE_ADMIN" — prefix added automatically

// For checking multiple roles:
@PreAuthorize("hasAnyRole('ADMIN', 'LAWYER')")

// For checking the logged-in user matches the resource owner:
@PreAuthorize("#userId == authentication.principal.username")
```

**Caution — the self-invocation trap with @PreAuthorize:**

`@PreAuthorize` works through Spring AOP (a proxy wraps your bean). If you call a `@PreAuthorize`-annotated method from **within the same class**, the proxy is bypassed and the authorization check is skipped silently. This is one of the most common Spring Security bugs in production.

```java
// ❌ DANGEROUS: self-invocation bypasses @PreAuthorize
@Service
public class CaseService {
    public void doSomething() {
        this.getAdminOnlyData(); // proxy bypassed! No auth check!
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<Case> getAdminOnlyData() { ... }
}

// ✅ CORRECT: inject the bean, don't call self
@Service
public class CaseService {
    @Autowired
    private CaseService self; // Spring injects the proxy

    public void doSomething() {
        self.getAdminOnlyData(); // goes through proxy → auth check runs
    }
}
```

---

## 10. CORS Configuration

CORS (Cross-Origin Resource Sharing) is the browser's mechanism to decide whether a web app on `http://localhost:5173` (VaadVivaad's React app) is allowed to call an API on `http://localhost:8080`.

**Why configure CORS in SecurityConfig and not just `@CrossOrigin`?**

When a browser makes a CORS **preflight request** (HTTP `OPTIONS` method), Spring Security intercepts it before your controller sees it. If Spring Security rejects the preflight (because it lacks auth), the browser never sends the actual request, and `@CrossOrigin` on your controller is never reached. You must configure CORS at the security layer so preflight requests pass through correctly.

**VaadVivaad's CORS configuration:**
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(
        "http://localhost:5173",   // Vite dev server
        "http://localhost:3000"    // alternative React port
    ));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
}
```

**Why `allowCredentials(true)`?** This allows the browser to include cookies in cross-origin requests. Even if you're not using cookies for auth (you're using JWT in headers), setting this allows future cookie-based features and is commonly needed when `Authorization` headers are sent.

**Security note:** You cannot combine `allowedOrigins("*")` with `allowCredentials(true)` — browsers reject this. You must specify exact origins, which VaadVivaad does correctly.

Node equivalent:
```javascript
app.use(cors({
    origin: ['http://localhost:5173', 'http://localhost:3000'],
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Authorization', 'Content-Type'],
    credentials: true
}));
```

---

## 11. Public vs Protected Routes

VaadVivaad's route access model:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(
        "/api/auth/**",         // POST /api/auth/login, POST /api/auth/register
        "/api/test/**",         // Test/debug endpoints
        "/actuator/health",     // Health check for load balancers, Docker
        "/v3/api-docs/**",      // OpenAPI JSON spec
        "/swagger-ui/**"        // Swagger interactive UI
    ).permitAll()
    .anyRequest().authenticated()
)
```

**`permitAll()` does not mean the filter doesn't run.** `JwtAuthFilter` still executes for every request — including public ones. The difference is that if a request to `/api/auth/login` has no token, the filter passes through without setting authentication, and then `.permitAll()` allows the request to proceed anyway. If the same request carries a valid token, the filter sets the authentication and the request proceeds — now with identity attached, even on a public route.

**`authenticated()`** requires that `SecurityContextHolder.getContext().getAuthentication()` is non-null and `isAuthenticated()` returns true. If not, Spring Security responds with **401 Unauthorized** before the request reaches any controller.

**Pattern matching note:** `/api/auth/**` matches:
- `/api/auth/login`
- `/api/auth/register`
- `/api/auth/anything/deeper/nested`

The `**` in Spring Security 6 uses `PathPatternParser` by default, which is more strict about `/` separators than the old `AntPathMatcher`.

---

## 12. Password Hashing

**VaadVivaad's password encoder:**
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

**Why BCrypt specifically?**

BCrypt has three properties that make it the right choice for passwords:

1. **Adaptive cost factor.** BCrypt has a "work factor" (default: 10 in Spring's implementation). This means hashing takes ~100ms on modern hardware. As hardware gets faster, you increase the work factor. This keeps brute-force attacks slow even as computing power grows. MD5 and SHA-256 are designed to be *fast* — catastrophic for password storage.

2. **Built-in salt.** BCrypt generates a random salt and embeds it in the hash output. You never manage salts manually. The stored hash looks like: `$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy`. The `$10$` is the work factor, the next 22 chars are the salt.

3. **One-way.** You cannot reverse a BCrypt hash. To verify a password, BCrypt re-runs the hash with the same salt and compares. `passwordEncoder.matches(rawPassword, storedHash)` does this.

**In AuthService.register():**
```java
user.setPasswordHash(passwordEncoder.encode(request.password()));
// rawPassword → BCrypt hash → stored in DB
// User's plain password never touches the database
```

**In AuthService.login():** BCrypt comparison happens inside `DaoAuthenticationProvider` when `authenticationManager.authenticate()` is called — you don't call `matches()` explicitly.

Node equivalent:
```javascript
// bcryptjs or bcrypt package — same algorithm
const hash = await bcrypt.hash(password, 10);  // 10 = work factor
await bcrypt.compare(rawPassword, storedHash);  // verify
```

---

## 13. Timing-Safe Login — The Enumeration Attack

**The attack:** An attacker can probe your login endpoint to find valid email addresses:
- `user@real.com` + wrong password → 401 (takes 200ms, BCrypt runs)
- `nobody@fake.com` + wrong password → 401 (takes 1ms, user not found, skipped BCrypt)

The time difference reveals which email addresses exist in your database. This is a **username enumeration attack**.

**The fix:** Always run BCrypt comparison, even when the user doesn't exist.

```java
// ✅ Timing-safe pattern:
public void login(LoginRequest request) {
    Optional<User> userOpt = userRepository.findByEmail(request.email());

    // Always run BCrypt — takes the same time whether user exists or not
    String hashToCheck = userOpt
            .map(User::getPasswordHash)
            .orElse("$2a$10$invalidhashtopreventtimingattack........");

    boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

    if (userOpt.isEmpty() || !passwordMatches) {
        throw new BadCredentialsException("Invalid credentials");
    }
    // proceed with token generation
}
```

**Does VaadVivaad do this?** No — VaadVivaad uses `authenticationManager.authenticate()` in `AuthService.login()`. Let's trace what `DaoAuthenticationProvider` does:

```java
// Inside DaoAuthenticationProvider (Spring source):
UserDetails user = retrieveUser(username, authentication);  // calls loadUserByUsername
// If user not found → throws UsernameNotFoundException immediately
// → Spring converts this to BadCredentialsException (to not expose the difference)
// But crucially: BCrypt does NOT run if user not found
```

Spring Security actually **does** handle this partially — it converts `UsernameNotFoundException` to `BadCredentialsException` by default (so the error message is the same). But since BCrypt doesn't run, the response time is still faster for non-existent users. VaadVivaad does not implement full timing-safe protection.

**The gap:** For production, set `DaoAuthenticationProvider.setHideUserNotFoundExceptions(false)` only if you want to distinguish errors in logs. And implement a dummy BCrypt check when user is not found. This is a real production concern for user-facing applications.

---

## 14. Token Expiry and the Refresh Token Gap

**VaadVivaad's token lifetime:**
```yaml
# application.properties / yml
application.security.jwt.expiration: 86400000  # 24 hours in milliseconds
```

**What happens when a token expires:**

1. User makes request with expired token
2. `JwtAuthFilter` calls `jwtService.extractUsername(jwt)`
3. `Jwts.parser().parseSignedClaims(token)` throws `ExpiredJwtException` (a `JwtException`)
4. VaadVivaad currently does not catch this — the exception bubbles up
5. Spring Security returns **401 Unauthorized** with a generic error
6. The React frontend needs to catch 401s and redirect to login

**The missing piece — Refresh Token Flow:**

VaadVivaad does not implement refresh tokens. This is a real architectural gap. The industry-standard approach:

```
Access Token:   15 minutes (short-lived, sent with every request)
Refresh Token:  7 days (long-lived, used only to get new access tokens)
```

**How refresh tokens work:**
1. Login returns both `accessToken` and `refreshToken`
2. `refreshToken` is stored in DB (so it can be revoked)
3. When `accessToken` expires (401), client calls `POST /api/auth/refresh` with the `refreshToken`
4. Server validates `refreshToken` (not expired, exists in DB, not revoked)
5. Server returns new `accessToken` (and optionally a new `refreshToken`)

**Why VaadVivaad's 24h token is a risk:**
- If a JWT is stolen, the attacker has 24 hours of access with no way to revoke it
- JWTs are stateless — you cannot invalidate a specific token without a denylist
- A refresh token stored in DB can be deleted (instant revocation)

**Calling this out in interviews:** This is exactly the kind of gap a senior developer is expected to spot. "VaadVivaad uses 24-hour access tokens with no refresh token mechanism. This means token revocation (e.g., on logout or compromise) is not possible without either reducing expiry time significantly or implementing a token denylist."

---

## 15. Common Spring Security Mistakes

### 1. Self-invocation bypasses @PreAuthorize
Already covered in section 9. The proxy cannot intercept internal method calls.

### 2. CSRF disabled incorrectly
```java
// ✅ Correct — CSRF off for stateless REST API
.csrf(AbstractHttpConfigurer::disable)

// ❌ Wrong — CSRF off for a traditional session-based web app
// This creates real security holes for form-based applications
```

### 3. Not clearing SecurityContext on logout
If you implement stateful logout (e.g., token denylist), ensure you clear the context:
```java
SecurityContextHolder.clearContext();
```
Without this, the `SecurityContext` in `ThreadLocal` persists until the thread is recycled.

### 4. Using `antMatchers()` instead of `requestMatchers()`
In Spring Security 6, `antMatchers()` is removed. Use `requestMatchers()` with the same patterns.

### 5. Catching exceptions in JwtAuthFilter and returning responses
```java
// ❌ Wrong pattern:
try {
    String username = jwtService.extractUsername(jwt);
} catch (JwtException e) {
    response.setStatus(401);
    response.getWriter().write("Invalid token");
    return; // stops chain but doesn't use Spring's error handling
}

// ✅ Better: let exceptions propagate and configure an AuthenticationEntryPoint
// Or catch and just pass through (the URL rules will deny the request)
```

### 6. Hardcoding secrets in application.properties
```yaml
# ❌ Never commit this:
application.security.jwt.secret-key: myHardcodedSecret123

# ✅ Use environment variables:
application.security.jwt.secret-key: ${JWT_SECRET}
```

### 7. Confusing `permitAll()` with "filter doesn't run"
As explained in section 11 — `permitAll()` does not stop your JWT filter from running. It just means the URL is accessible even if authentication fails.

### 8. Using weak JWT secrets
For HS256, your secret needs to be at least 256 bits (32 bytes). A short string like "secret" is cryptographically weak. Generate a proper key:
```bash
# Generate a 256-bit Base64-encoded secret:
openssl rand -base64 32
```

---

## 16. Node.js → Java Concept Map

| Node.js Concept | Spring Security Equivalent | Notes |
|---|---|---|
| `app.use(authenticate)` | `SecurityFilterChain` bean | Spring's is more structured |
| `express-jwt` middleware | `JwtAuthFilter extends OncePerRequestFilter` | Same contract: read header, validate, set user |
| `req.user = decoded` | `SecurityContextHolder.getContext().setAuthentication()` | ThreadLocal vs request-attached |
| `passport.js LocalStrategy` | `DaoAuthenticationProvider` | Both load user + compare password |
| `passport.use(strategy)` | `.authenticationProvider(provider)` | Registration pattern |
| `passport.authenticate()` | `authenticationManager.authenticate()` | Trigger authentication |
| `bcryptjs.compare()` | `passwordEncoder.matches()` | Same BCrypt algorithm |
| `bcryptjs.hash()` | `passwordEncoder.encode()` | Same |
| `jsonwebtoken.sign()` | `Jwts.builder().signWith().compact()` | JJWT's builder pattern |
| `jsonwebtoken.verify()` | `Jwts.parser().parseSignedClaims()` | Throws on invalid/expired |
| `cors()` middleware | `CorsConfigurationSource` bean | Must be in security layer in Spring |
| Route-level `authenticate` middleware | `authorizeHttpRequests()` matchers | Declarative in Spring |
| `@PreAuthorize` equivalent | No direct equivalent; roles checked in middleware or route handler | Spring's is more declarative |
| `process.env.JWT_SECRET` | `@Value("${application.security.jwt.secret-key}")` | Both from environment |
| `next()` in middleware | `filterChain.doFilter(request, response)` | Exactly the same concept |

**The core philosophical difference:** Node middleware is **procedural** — you write the logic. Spring Security is **declarative** — you configure the framework and plug in your components. Spring's approach produces more boilerplate up front but less hand-rolled security logic (which means fewer custom vulnerabilities).

---

## 17. Senior Interview Q&A

**Q1: "Why does VaadVivaad call `authenticationManager.authenticate()` in `AuthService.login()` instead of directly comparing the password?"**

A: `authenticationManager.authenticate()` is the correct Spring Security idiom for login. It delegates to `DaoAuthenticationProvider`, which handles loading the user, comparing the BCrypt hash, and throwing the appropriate Spring Security exceptions (`BadCredentialsException`, `DisabledException`, `LockedException`). Doing password comparison directly would bypass Spring Security's exception hierarchy and any other authentication providers or listeners (like `AuthenticationEventPublisher`). It also means Spring Security can properly publish a `AuthenticationSuccessEvent` or `AuthenticationFailureEvent`. The idiomatic approach stays extensible.

---

**Q2: "Explain what happens when a JWT token is tampered with."**

A: `Jwts.parser().parseSignedClaims(token)` recomputes the HMAC-SHA256 signature using our secret key and compares it to the signature in the token. If any part of the header or payload was modified, the signatures won't match and a `SignatureException` (subclass of `JwtException`) is thrown. In VaadVivaad's current implementation, this exception propagates from `JwtAuthFilter` and Spring Security returns a 401. The JWT design is such that you cannot modify claims without knowing the secret key — the signature is a cryptographic commitment to the exact bytes of the payload.

---

**Q3: "Why does `JwtAuthFilter` not return 401 directly when there's no Authorization header?"**

A: The filter's responsibility is only to **identify** the user, not to **enforce access control**. If there's no token, the filter passes the request through unauthenticated. The `authorizeHttpRequests()` layer then decides whether the route requires authentication. This separation allows public routes (login, register, swagger) to work correctly without tokens. If the filter returned 401 on missing header, no public route could ever be accessed — it would block everything.

---

**Q4: "What does `@EnableMethodSecurity` actually enable, and what's the difference from the old `@EnableGlobalMethodSecurity`?"**

A: `@EnableMethodSecurity` (Spring Security 6+) enables annotation-based method-level security: `@PreAuthorize`, `@PostAuthorize`, `@PreFilter`, `@PostFilter`. The old `@EnableGlobalMethodSecurity(prePostEnabled = true)` is deprecated. The modern version uses Spring AOP to create proxies around beans. The critical caveat: it only works on Spring-managed beans called through the proxy. Self-invocation bypasses the proxy entirely. In VaadVivaad, `@EnableMethodSecurity` is declared on `SecurityConfig` without parameters — by default, pre/post authorization is enabled.

---

**Q5: "The `UserDetailsServiceImpl` calls the DB on every authenticated request. How would you optimize this in production?"**

A: Several options exist. First, consider caching: annotate `loadUserByUsername()` with `@Cacheable("users")` and configure a cache (Caffeine for in-memory, Redis for distributed). Second, evaluate whether the DB call is actually necessary — if all authorization data is in the JWT claims (role, user ID), you could skip the DB call for many requests and only hit the DB when you need data not in the token. Third, use a connection pool (HikariCP, which Spring Boot configures by default) to minimize connection overhead. Fourth, for read-heavy workloads, point `loadUserByUsername()` at a read replica. The trade-off with caching is staleness: if a user is banned mid-session, the cached user details might allow their requests for the cache TTL duration.

---

**Q6: "VaadVivaad uses 24-hour JWTs with no refresh token. What are the security implications and how would you fix it?"**

A: The core problem is that JWTs are stateless and cannot be revoked. If a token is stolen (via XSS, logging, network intercept), the attacker has 24 hours of valid access. There is no server-side mechanism to say "this specific token is now invalid." Fixes in order of increasing completeness: (1) Reduce access token lifetime to 15 minutes — limits the attack window. (2) Implement a refresh token pattern: short-lived access token + long-lived refresh token stored in DB; logout deletes the refresh token from DB. (3) Implement a token denylist: a Redis set of invalidated JWT IDs (`jti` claim); check every request against it. (4) Implement token rotation: each use of a refresh token issues a new one and invalidates the old. VaadVivaad needs option 2 at minimum for a production application.

---

**Q7: "Why does `UsernamePasswordAuthenticationToken` have both a two-argument and three-argument constructor?"**

A: The two-arg constructor creates an **unauthenticated** token — `isAuthenticated()` returns false. It is used to carry credentials **into** `AuthenticationManager.authenticate()`. The three-arg constructor creates an **authenticated** token — `isAuthenticated()` returns true. It is used **after** authentication is proven (by the filter or by the AuthenticationManager) to represent "this user is verified." Spring Security checks `isAuthenticated()` on the token stored in `SecurityContextHolder`. If you used the two-arg constructor in the filter, every request would appear unauthenticated even after JWT validation. This is a subtle but critical distinction.

---

**Q8: "What is `WebAuthenticationDetailsSource().buildDetails(request)` doing in JwtAuthFilter?"**

A: It captures metadata about the HTTP request — specifically the remote IP address and the session ID (if any) — and attaches it to the `Authentication` object. This metadata is used for: (1) Security auditing and logging (know which IP authenticated), (2) Spring Security events (if you listen for `AuthenticationSuccessEvent`, you get the details), (3) Some security policies that restrict authentication by IP. In VaadVivaad it's mainly boilerplate good practice. Removing it would not break authentication but would lose audit metadata.

---

**Q9: "How does Spring Security know to apply `@PreAuthorize` to a method — what's happening under the hood?"**

A: `@EnableMethodSecurity` tells Spring to create AOP proxies around beans that have security annotations. When `CaseService` is injected into a controller, Spring actually injects a **proxy** of `CaseService` — a generated subclass that wraps each method. When `controller.caseService.getAdminOnlyData()` is called, it calls the proxy's method, not the real method. The proxy checks the annotation, evaluates the SpEL expression (`hasRole('ADMIN')`), queries the `SecurityContextHolder` for the current `Authentication`, checks if it has the `ROLE_ADMIN` authority, and either calls the real method or throws `AccessDeniedException`. This is why self-invocation breaks it: internal calls bypass the proxy entirely.

---

**Q10: "If two concurrent requests from the same user arrive at the same time, how does SecurityContextHolder prevent them from interfering with each other?"**

A: `SecurityContextHolder` uses `ThreadLocalSecurityContextHolderStrategy` by default, which stores the `SecurityContext` in a `ThreadLocal<SecurityContext>`. A `ThreadLocal` is a variable that has a separate value for each thread. Since each HTTP request is handled by a separate thread from the thread pool, concurrent requests from the same user get isolated `SecurityContext` instances — they cannot see or modify each other's authentication state. After each request completes, `clearContext()` cleans up the `ThreadLocal` before the thread returns to the pool. This is thread safety by isolation — no locks needed.

---

## 18. Senior Differentiators

These are the things junior and mid-level developers typically don't know or can't articulate. Knowing these places you in the "senior" bucket.

### You understand the filter chain topology

You can describe the order: `CorsFilter` → `SecurityContextPersistenceFilter` → `LogoutFilter` → `JwtAuthFilter` (custom) → `UsernamePasswordAuthenticationFilter` → `ExceptionTranslationFilter` → `FilterSecurityInterceptor`. You know your custom filter's position matters and why `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` places it correctly.

### You know the difference between Authentication and Authorization

Authentication: "Who are you?" (handled by `JwtAuthFilter` + `SecurityContextHolder`)
Authorization: "What are you allowed to do?" (handled by `authorizeHttpRequests()` + `@PreAuthorize`)
Many developers conflate these. Spring Security separates them deliberately.

### You can articulate the UserDetails design decision

VaadVivaad keeps `com.vaadvivaad.user.entity.User` (JPA entity) separate from Spring Security's `UserDetails`. An alternative is having the entity implement `UserDetails`. You can explain the trade-off: entity-implements-UserDetails is simpler but couples domain model to Spring Security, making testing harder. Separate UserDetails is more verbose but cleaner.

### You spot the timing attack gap

VaadVivaad's `authenticationManager.authenticate()` delegates to `DaoAuthenticationProvider`. Spring converts `UsernameNotFoundException` to `BadCredentialsException` (obscures user existence via error message) but BCrypt still doesn't run for non-existent users, leaving a timing side-channel. You know to note this and propose the fix.

### You identify the refresh token gap unprompted

24-hour JWTs with no refresh token mechanism means no revocation capability. You can propose the full refresh token pattern and explain why the access/refresh split exists.

### You understand why CORS belongs in SecurityConfig

Not just "because that's how you do it" — but because Spring Security intercepts preflight OPTIONS requests before they reach `@CrossOrigin` on controllers. If you configure CORS only at the controller level, preflight requests fail and browsers block all cross-origin calls.

### You know the proxy limitation of @PreAuthorize

Self-invocation bypasses AOP proxies. You've seen or can reason about production bugs caused by this. You know the fix: inject the proxy via `@Autowired private MyService self`.

### You can reason about SecurityContextHolder thread safety

ThreadLocal gives per-thread isolation. You know why Spring clears it after each request (thread pool reuse) and what happens in async scenarios (context propagation to child threads is not automatic — requires `DelegatingSecurityContextExecutor`).

### You think about token storage on the client side

VaadVivaad issues JWTs. Where the React app stores them matters:
- `localStorage`: persists across tabs/sessions, vulnerable to XSS (any JS on the page can read it)
- `sessionStorage`: tab-isolated, still XSS-vulnerable  
- `HttpOnly cookie`: JS cannot read it, immune to XSS, but needs CSRF protection

VaadVivaad's CORS config allows credentials (`setAllowCredentials(true)`), suggesting future cookie support. Knowing this trade-off demonstrates security depth.

### You can describe what "stateless" actually means in practice

`SessionCreationPolicy.STATELESS` tells Spring never to create or use `HttpSession`. Every request is authenticated from scratch using the JWT. There is no server-side session store, no sticky sessions requirement in load balancing, no `JSESSIONID` cookie. Horizontal scaling is trivial — any server can handle any request.

---

*File: `02_spring_security_jwt.md` | Project: VaadVivaad | Last updated: 2026-04-03*
*Code references: Spring Boot 3.4.4, Spring Security 6.x, JJWT 0.12+, Java 21*
