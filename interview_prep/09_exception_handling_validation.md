# 09 — Exception Handling & Validation in Spring Boot

> **Project:** VaadVivaad — Spring Boot 3.4.4 / Java 21
> **Audience:** Knows Node.js/Express well, learning Java/Spring Boot
> **Goal:** Understand the *why* behind each pattern, not just the syntax

---

## Table of Contents

1. [The Mental Model — From Node Middleware to Spring Advice](#1-the-mental-model)
2. [@ControllerAdvice and @RestControllerAdvice](#2-controlleradvice)
3. [@ExceptionHandler — Mapping Exceptions to Responses](#3-exceptionhandler)
4. [VaadVivaad's GlobalExceptionHandler — Full Walkthrough](#4-vaadvivaads-globalexceptionhandler)
5. [Custom Exceptions — ResourceNotFoundException, CnrValidationException, ScraperException](#5-custom-exceptions)
6. [Checked vs Unchecked Exceptions — Java's Most Important Distinction](#6-checked-vs-unchecked)
7. [ApiResponse<T> — Consistent Success Wrapper](#7-apiresponse)
8. [ErrorResponse — Consistent Error Wrapper](#8-errorresponse)
9. [JSR-380 Bean Validation — Annotations that Enforce Contracts](#9-jsr-380-bean-validation)
10. [@Valid vs @Validated](#10-valid-vs-validated)
11. [Validation in Controllers — The @Valid @RequestBody Pattern](#11-validation-in-controllers)
12. [Extracting Field Errors from MethodArgumentNotValidException](#12-extracting-field-errors)
13. [Custom Validators — When Annotations Aren't Enough](#13-custom-validators)
14. [@PathVariable and @RequestParam Validation](#14-pathvariable-requestparam-validation)
15. [HTTP Status Codes — Complete Practical Guide](#15-http-status-codes)
16. [ResponseEntity — Full Control Over HTTP Responses](#16-responseentity)
17. [Logging Exceptions Properly](#17-logging-exceptions-properly)
18. [Node → Java Comparison Table](#18-node-java-comparison)
19. [Senior Interview Q&A](#19-senior-interview-qa)
20. [Senior Differentiators](#20-senior-differentiators)

---

## 1. The Mental Model

### In Node/Express, error handling is middleware

```js
// Node: errors bubble up to the error middleware
app.get('/users/:id', async (req, res, next) => {
  try {
    const user = await findUser(req.params.id);
    if (!user) throw new NotFoundError('User not found');
    res.json(user);
  } catch (err) {
    next(err); // pass to error middleware
  }
});

// Central error handler — receives ALL unhandled errors
app.use((err, req, res, next) => {
  if (err instanceof NotFoundError) {
    return res.status(404).json({ error: err.message });
  }
  res.status(500).json({ error: 'Internal server error' });
});
```

Problems with this approach:
- You have to `try/catch` + `next(err)` in every route handler manually.
- It's easy to forget `next(err)` and leave the request hanging.
- The error handler is one big file that grows forever.

### In Spring, exceptions bubble automatically — no try/catch needed in controllers

```java
// Spring: controller just throws. No try/catch needed.
@GetMapping("/users/{id}")
public ResponseEntity<ApiResponse<UserResponse>> getUser(@PathVariable UUID id) {
    UserResponse user = userService.findById(id); // throws ResourceNotFoundException if not found
    return ResponseEntity.ok(ApiResponse.success(user));
}

// ONE central handler in GlobalExceptionHandler catches it:
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of(404, "Not Found", ex.getMessage()));
}
```

The flow is identical conceptually:

```
Node:   Controller → throws → next(err) → Error Middleware → Response
Spring: Controller → throws → (Spring catches) → @ExceptionHandler → Response
```

The difference: Spring's AOP proxy intercepts the exception automatically. You don't have to call `next(err)`. The exception propagates up the call stack naturally, and `@ControllerAdvice` intercepts it before Spring sends a response.

---

## 2. @ControllerAdvice

### What it is

`@ControllerAdvice` is a **specialization of `@Component`** that applies cross-cutting behavior to all `@Controller` and `@RestController` classes — globally, not per-controller.

Think of it as a **plugin that wraps around every controller in your application**.

```java
@ControllerAdvice  // applies to ALL controllers
public class GlobalExceptionHandler {

    @ExceptionHandler(SomeException.class)
    public ResponseEntity<ErrorResponse> handle(SomeException ex) {
        // ...
    }
}
```

### @RestControllerAdvice = @ControllerAdvice + @ResponseBody

`@ControllerAdvice` handler methods need `@ResponseBody` to serialize the return value to JSON.
`@RestControllerAdvice` adds `@ResponseBody` automatically — just like `@RestController` vs `@Controller`.

```java
// These two are equivalent:

@ControllerAdvice
public class Handler {
    @ExceptionHandler(SomeException.class)
    @ResponseBody  // must add manually
    public ResponseEntity<ErrorResponse> handle(SomeException ex) { ... }
}

@RestControllerAdvice  // @ControllerAdvice + @ResponseBody built in
public class Handler {
    @ExceptionHandler(SomeException.class)
    public ResponseEntity<ErrorResponse> handle(SomeException ex) { ... }
}
```

### Scoping @ControllerAdvice (advanced)

You can limit which controllers a `@ControllerAdvice` applies to:

```java
// Only applies to controllers in this package:
@ControllerAdvice(basePackages = "com.vaadvivaad.auth")

// Only applies to these specific controller classes:
@ControllerAdvice(assignableTypes = {AuthController.class, CaseLookupController.class})

// Only applies to controllers annotated with this:
@ControllerAdvice(annotations = RestController.class)
```

VaadVivaad uses an unscoped `@RestControllerAdvice` — it catches exceptions from all controllers, which is the correct approach for a unified error response format.

---

## 3. @ExceptionHandler — Mapping Exceptions to Responses

`@ExceptionHandler` annotates a method inside a `@ControllerAdvice` (or inside a specific controller) to handle a particular exception type.

### Basic structure

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
    // Build a meaningful error response
    ErrorResponse error = ErrorResponse.of(404, "Not Found", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
}
```

### Multiple exception types in one handler

```java
@ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
public ResponseEntity<ErrorResponse> handleBadInput(RuntimeException ex) {
    ErrorResponse error = ErrorResponse.of(400, "Bad Request", ex.getMessage());
    return ResponseEntity.badRequest().body(error);
}
```

### Method parameter injection

Spring can inject useful objects into `@ExceptionHandler` methods:

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handle(
        ResourceNotFoundException ex,
        HttpServletRequest request,   // the incoming request
        WebRequest webRequest) {      // or WebRequest
    String path = request.getRequestURI();
    log.warn("Resource not found at {}: {}", path, ex.getMessage());
    // ...
}
```

### Handler resolution order

When an exception matches multiple handlers, Spring picks the most specific one:

```
Exception hierarchy: BadCredentialsException → AuthenticationException → RuntimeException → Exception
If handlers exist for both BadCredentialsException and AuthenticationException,
the BadCredentialsException handler wins because it is more specific.
```

This is why VaadVivaad's `@ExceptionHandler(Exception.class)` is the last resort — everything more specific gets handled by a more targeted method first.

---

## 4. VaadVivaad's GlobalExceptionHandler — Full Walkthrough

```java
// com/vaadvivaad/common/exception/GlobalExceptionHandler.java

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- HANDLER 1: Domain-level "not found" ---
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.of(
            HttpStatus.NOT_FOUND.value(),   // 404
            "Not Found",
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // --- HANDLER 2: CNR-specific business validation ---
    @ExceptionHandler(CnrValidationException.class)
    public ResponseEntity<ErrorResponse> handleCnrValidation(CnrValidationException ex) {
        log.warn("CNR validation failed: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.of(
            HttpStatus.BAD_REQUEST.value(), // 400
            "Validation Error",
            ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // --- HANDLER 3: JSR-380 field-level validation failure ---
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage())
        );
        log.warn("Validation failed: {}", fieldErrors);
        ErrorResponse error = ErrorResponse.withFieldErrors(
            HttpStatus.BAD_REQUEST.value(), // 400
            "Validation Failed",
            "One or more fields have errors",
            fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // --- HANDLER 4: Wrong login credentials ---
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        ErrorResponse error = ErrorResponse.of(
            HttpStatus.UNAUTHORIZED.value(), // 401
            "Unauthorized",
            "Invalid email or password"     // intentionally vague — security best practice
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    // --- HANDLER 5: Duplicate data, conflicting state ---
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        ErrorResponse error = ErrorResponse.of(
            HttpStatus.CONFLICT.value(),    // 409
            ex.getMessage(),
            request.getRequestURI()         // includes which endpoint caused the conflict
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // --- HANDLER 6: Last resort — catch-all for unexpected errors ---
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);  // log full stack trace — this is truly unexpected
        ErrorResponse error = ErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(), // 500
            "Internal Server Error",
            "An unexpected error occurred. Please try again later." // never expose internal details
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
```

### Key design decisions in this handler

| Decision | Rationale |
|---|---|
| `log.warn()` for 4xx | Expected failures — known paths, no alarm needed |
| `log.error("Unexpected error: ", ex)` for 500 | The `, ex` passes the Throwable — logs full stack trace |
| "Invalid email or password" (not "wrong password") | Security: never reveal which part was wrong |
| Generic 500 hides internal message | Never leak stack traces or DB errors to clients |
| `request.getRequestURI()` in CONFLICT handler | Helps client know which endpoint caused the conflict |

---

## 5. Custom Exceptions

### Why custom exceptions instead of using generic RuntimeException?

Imagine your service catches a failed DB call. If you just `throw new RuntimeException("something failed")`, the `GlobalExceptionHandler` can only route it to the generic 500 handler. But if you throw `throw new ResourceNotFoundException("Case", "cnrNumber", cnr)`, the handler routes it to the specific 404 handler with a clean message.

Custom exceptions = **type-safe routing of error conditions**.

### VaadVivaad's exception hierarchy

```
RuntimeException (Java built-in)
├── ResourceNotFoundException        — common/exception
├── CnrValidationException           — common/exception  
└── ScraperException                 — scraper/exception
    └── CaptchaException             — scraper/exception (implied sub-case)
```

### ResourceNotFoundException

```java
// common/exception/ResourceNotFoundException.java
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    // Convenience constructor: builds "Case not found with cnrNumber: 'TNCH01001234567'"
    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
```

Usage in a service:

```java
public CaseResponse lookupById(UUID id) {
    return caseRepository.findById(id)
        .map(caseMapper::toResponse)
        .orElseThrow(() -> new ResourceNotFoundException("Case", "id", id));
    // produces: "Case not found with id: '550e8400-e29b-41d4-a716-446655440000'"
}
```

### CnrValidationException

```java
// common/exception/CnrValidationException.java
public class CnrValidationException extends RuntimeException {
    public CnrValidationException(String message) {
        super(message);
    }
}
```

This is separate from `ResourceNotFoundException` because CNR validation failure is **business logic** — the format is wrong before we even attempt a DB lookup. Separate exception = separate error message, separate HTTP status path.

### ScraperException and CaptchaException

```java
// scraper/exception/ScraperException.java
public class ScraperException extends RuntimeException {
    public ScraperException(String message) {
        super(message);
    }
    public ScraperException(String message, Throwable cause) {
        super(message, cause);
    }
}

// scraper/exception/CaptchaException.java
public class CaptchaException extends RuntimeException {
    public CaptchaException(String message) {
        super(message);
    }
    public CaptchaException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

The `Throwable cause` constructor is important: it preserves the original exception that caused this one. When you wrap a low-level exception:

```java
try {
    driver.get(ecourtsUrl);
} catch (WebDriverException e) {
    throw new ScraperException("Browser automation failed while accessing eCourts", e);
    // The WebDriverException is preserved as the "cause"
    // log.error("...", ex) will print BOTH the ScraperException and its WebDriverException cause
}
```

This is called **exception chaining** — the root cause is never lost.

---

## 6. Checked vs Unchecked Exceptions

This is Java-specific and has no direct Node equivalent. Understanding this is essential for Java interviews.

### The fundamental distinction

**Checked exceptions** — the compiler forces you to handle them.
**Unchecked exceptions** — you can ignore them; they propagate automatically.

```java
// CHECKED: extends Exception (not RuntimeException)
// Compiler DEMANDS you either catch it or declare "throws IOException"
public void readFile(String path) throws IOException {  // must declare
    FileInputStream fis = new FileInputStream(path);    // FileInputStream throws IOException
}

// Caller must handle it:
try {
    readFile("/etc/passwd");
} catch (IOException e) {
    // MUST handle this
}

// UNCHECKED: extends RuntimeException
// Compiler doesn't care — exception propagates freely
public void divide(int a, int b) {
    if (b == 0) throw new ArithmeticException("divide by zero"); // no throws declaration needed
}

// Caller doesn't have to handle it:
divide(10, 0); // ArithmeticException will propagate up naturally
```

### Why all VaadVivaad custom exceptions extend RuntimeException

1. **Controller layer stays clean** — no forced try/catch in every method.
2. **Exception routes to GlobalExceptionHandler naturally** — no `throws` declarations cluttering method signatures.
3. **@Transactional auto-rollback** — Spring's `@Transactional` only rolls back the transaction on unchecked exceptions by default. If a service throws a checked exception, Spring commits the transaction (unless you configure `rollbackFor`).

```java
@Transactional
public CaseResponse createCase(CreateCaseRequest request) {
    // If this throws ResourceNotFoundException (unchecked):
    //   → Spring automatically rolls back the transaction
    //   → Exception propagates to GlobalExceptionHandler
    //   → Clean 404 response to client
    
    // If this threw IOException (checked):
    //   → Spring COMMITS the transaction (wrong!)
    //   → You'd have to add rollbackFor = IOException.class
    //   → Every service method signature would need "throws IOException"
}
```

### When would you use checked exceptions?

Only when **the caller is expected to recover from the failure** — for example, "file not found, use a default instead." In web API services, callers (the HTTP client) cannot recover from your internal errors; they only see the HTTP response. So unchecked exceptions throughout the service layer are the correct choice.

---

## 7. ApiResponse<T> — Consistent Success Wrapper

### The problem it solves

Without a wrapper, different endpoints return different shapes:

```json
GET /api/users/1    → { "id": "...", "name": "..." }
GET /api/cases      → [ {...}, {...} ]
POST /api/auth/login → { "token": "...", "user": {...} }
```

The frontend can't write a single interceptor to handle all responses. A wrapper enforces a consistent shape for every single response.

### VaadVivaad's ApiResponse

```java
// common/dto/ApiResponse.java
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(data, "Success");
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, LocalDateTime.now());
    }
}
```

This is a **Java record** — an immutable data carrier introduced in Java 16. Records auto-generate: constructor, getters (via `fieldName()`), `equals()`, `hashCode()`, `toString()`.

### The `<T>` generic type parameter

`ApiResponse<T>` means "an API response that wraps some data of type T." The type is inferred at usage:

```java
// T is inferred as AuthResponse
ApiResponse<AuthResponse> response = ApiResponse.success(authResponse);

// T is inferred as Page<CaseResponse>
ApiResponse<Page<CaseResponse>> paged = ApiResponse.success(casePage, "Cases retrieved");

// T is inferred as Void (no data)
ApiResponse<Void> empty = ApiResponse.success(null, "Deleted successfully");
```

### How controllers use it

```java
// AuthController.java

@PostMapping("/register")
public ResponseEntity<ApiResponse<AuthResponse>> register(
        @Valid @RequestBody RegisterRequest request) {
    AuthResponse authResponse = authService.register(request);
    return ResponseEntity
            .status(HttpStatus.CREATED)    // 201
            .body(ApiResponse.success(authResponse));
}

@PostMapping("/login")
public ResponseEntity<ApiResponse<AuthResponse>> login(
        @Valid @RequestBody LoginRequest request) {
    AuthResponse authResponse = authService.login(request);
    return ResponseEntity.ok(ApiResponse.success(authResponse));  // 200
}
```

Every successful response looks like:

```json
{
  "success": true,
  "message": "Success",
  "data": { "token": "eyJ...", "user": { ... } },
  "timestamp": "2026-04-03T10:30:00"
}
```

### Consistency between success and error paths

Notice that `ErrorResponse` (not `ApiResponse`) is used for errors. This is a deliberate design choice in VaadVivaad:

- **Success path** → `ApiResponse<T>` with typed `data`
- **Error path** → `ErrorResponse` with `status`, `error`, `fieldErrors`

Some teams use `ApiResponse` for both (setting `success: false` for errors). Either approach is valid — what matters is that it is consistent across the entire API.

---

## 8. ErrorResponse

### VaadVivaad's ErrorResponse

```java
// common/exception/ErrorResponse.java
public record ErrorResponse(
    int status,                       // HTTP status code: 404, 400, 500
    String error,                     // short error type: "Not Found", "Validation Failed"
    String message,                   // human-readable description
    Map<String, String> fieldErrors,  // field → message pairs (null for non-validation errors)
    LocalDateTime timestamp           // when the error occurred
) {
    // Standard error (no field errors)
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, null, LocalDateTime.now());
    }

    // Validation error (with field errors map)
    public static ErrorResponse withFieldErrors(int status, String error, String message,
                                                 Map<String, String> fieldErrors) {
        return new ErrorResponse(status, error, message, fieldErrors, LocalDateTime.now());
    }
}
```

Sample JSON output for a 404:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Case not found with cnrNumber: 'TNCH01001234567'",
  "fieldErrors": null,
  "timestamp": "2026-04-03T10:30:00"
}
```

Sample JSON output for a validation failure (400):

```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "One or more fields have errors",
  "fieldErrors": {
    "email": "Invalid email format",
    "password": "Password must be at least 8 characters"
  },
  "timestamp": "2026-04-03T10:30:00"
}
```

### RFC 7807 — Problem Details for HTTP APIs

RFC 7807 is the official standard for error response structure. Spring Boot 3.x supports it via `ProblemDetail`:

```java
// Spring Boot 3.x built-in Problem Details (RFC 7807)
ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
pd.setTitle("Resource Not Found");
pd.setProperty("cnrNumber", cnrNumber);  // custom extension properties
return ResponseEntity.status(404).body(pd);
```

RFC 7807 format:

```json
{
  "type": "https://api.vaadvivaad.com/errors/not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Case not found with cnrNumber: 'TNCH01001234567'",
  "instance": "/api/cases/lookup"
}
```

VaadVivaad uses a custom `ErrorResponse` record instead of `ProblemDetail`. Both are valid — custom gives more control, RFC 7807 is the emerging standard in Spring Boot 3.

---

## 9. JSR-380 Bean Validation

JSR-380 (also called "Bean Validation 2.0") is a Java standard for declaring validation rules as annotations on fields. In Spring Boot, it is implemented by the **Hibernate Validator** library (included automatically via `spring-boot-starter-validation`).

The mental model: in Node you'd use Joi or Zod schemas to validate request body shape. In Java, the constraints live directly on the DTO fields as annotations.

### Core annotations

| Annotation | What it validates | Example |
|---|---|---|
| `@NotNull` | Field is not null (but can be empty string) | `@NotNull String name` |
| `@NotBlank` | Not null AND not empty/whitespace (Strings only) | `@NotBlank String email` |
| `@NotEmpty` | Not null AND not empty collection/array/string | `@NotEmpty List<String> tags` |
| `@Email` | Valid email format | `@Email String email` |
| `@Size(min, max)` | String/collection length within range | `@Size(min=8, max=100)` |
| `@Pattern(regexp)` | Matches regex pattern | `@Pattern(regexp="^[6-9]\\d{9}$")` |
| `@Min(value)` | Numeric >= value | `@Min(1) int page` |
| `@Max(value)` | Numeric <= value | `@Max(100) int pageSize` |
| `@Positive` | Numeric > 0 | `@Positive int quantity` |
| `@PositiveOrZero` | Numeric >= 0 | `@PositiveOrZero int offset` |
| `@Future` | Date/time is in the future | `@Future LocalDate hearingDate` |
| `@Past` | Date/time is in the past | `@Past LocalDate filedDate` |

### VaadVivaad: LoginRequest

```java
// auth/dto/LoginRequest.java
public record LoginRequest(

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    String password
) {}
```

Two annotations on `email` — both must pass. If email is null → `@NotBlank` fails. If email is "notanemail" → `@Email` fails. The `message` attribute is what appears in the `fieldErrors` map in the response.

### VaadVivaad: RegisterRequest

```java
// auth/dto/RegisterRequest.java
public record RegisterRequest(

    @NotBlank(message = "Full name is required")
    String fullName,

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password,

    @Pattern(
        regexp = "^[6-9]\\d{9}$",
        message = "Invalid Indian mobile number"
    )
    String phoneNumber,   // optional field — no @NotBlank

    String role           // optional — no constraints at all
) {}
```

Notice `phoneNumber` has `@Pattern` but no `@NotBlank`. This means:
- If `phoneNumber` is absent from JSON → it's null → `@Pattern` is NOT triggered (Pattern only validates non-null values)
- If `phoneNumber` is present but malformed → `@Pattern` fails

This is the correct way to make optional fields with format constraints.

### VaadVivaad: CnrLookupRequest

```java
// lookup/dto/CnrLookupRequest.java
public record CnrLookupRequest(

    @NotBlank(message = "CNR number is required")
    @Pattern(
        regexp = "^[A-Z]{4}[0-9]{2}[A-Z0-9]{9,15}$",
        message = "Invalid CNR format. Expected format: TNCH010012345678"
    )
    String cnrNumber
) {}
```

The CNR regex: `^[A-Z]{4}[0-9]{2}[A-Z0-9]{9,15}$`
- `[A-Z]{4}` — 4 uppercase letters (state + district code, e.g., TNCH)
- `[0-9]{2}` — 2 digits (court number, e.g., 01)
- `[A-Z0-9]{9,15}` — 9 to 15 alphanumeric characters (case number + year)

This encodes domain knowledge about Indian court CNR numbers directly into the DTO.

---

## 10. @Valid vs @Validated

Both trigger Bean Validation, but they have different capabilities.

### @Valid (JSR-380 standard)

- Defined in `jakarta.validation.Valid`
- Triggers validation on the annotated parameter
- Supports **cascading validation** (validates nested objects)

```java
@PostMapping("/register")
public ResponseEntity<...> register(@Valid @RequestBody RegisterRequest request) {
    // Spring validates RegisterRequest fields before entering this method
}
```

### @Validated (Spring's extension)

- Defined in `org.springframework.validation.annotation.Validated`
- Everything `@Valid` does, plus:
- Supports **validation groups** (validate different subsets of constraints)
- Required on the **class level** for `@PathVariable`/`@RequestParam` validation

```java
// Validation groups — create interfaces to represent groups
public interface OnCreate {}
public interface OnUpdate {}

public record ProductRequest(
    @NotBlank(groups = OnCreate.class)  // required on create, not on update
    String name,

    @NotNull(groups = {OnCreate.class, OnUpdate.class})  // required on both
    BigDecimal price
) {}

// Controller uses @Validated with the group
@PostMapping
public ResponseEntity<...> create(@Validated(OnCreate.class) @RequestBody ProductRequest req) { ... }

@PutMapping("/{id}")
public ResponseEntity<...> update(@Validated(OnUpdate.class) @RequestBody ProductRequest req) { ... }
```

### For most use cases, @Valid is sufficient

VaadVivaad uses `@Valid` throughout — the right choice for a standard REST API without complex group validation needs.

---

## 11. Validation in Controllers — The @Valid @RequestBody Pattern

### How validation triggers

```java
// CaseLookupController.java
@PostMapping("/lookup")
public ResponseEntity<ApiResponse<CaseResponse>> lookupByCnr(
        @Valid @RequestBody CnrLookupRequest request) {
    CaseResponse caseResponse = caseLookupService.lookupByCnr(request.cnrNumber());
    return ResponseEntity.ok(ApiResponse.success(caseResponse, "Case found"));
}
```

The flow when a request arrives:

```
1. Spring receives POST /api/cases/lookup
2. Spring deserializes JSON body into CnrLookupRequest
3. @Valid triggers Hibernate Validator to check all constraints
4. IF validation passes → controller method body executes
5. IF validation fails → MethodArgumentNotValidException is thrown
                       → GlobalExceptionHandler.handleValidationErrors() catches it
                       → 400 response with field errors map returned
                       → controller method body NEVER executes
```

The controller code body never runs on invalid input. No manual validation code needed inside the method.

### The separation of concerns

```
DTO annotations (@NotBlank, @Email)    → WHAT is valid
@Valid on controller parameter         → WHEN to validate
GlobalExceptionHandler                 → HOW to respond to invalid input
Service layer                          → business logic (assumes input is valid)
```

This is the same contract that Joi/Zod schemas + Express middleware enforce, but in Java it's compile-time checked (annotations are on the class, not a separate schema file).

---

## 12. Extracting Field Errors from MethodArgumentNotValidException

This is what happens inside `GlobalExceptionHandler.handleValidationErrors()`:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
    
    // Step 1: Get BindingResult — contains ALL validation failures
    BindingResult bindingResult = ex.getBindingResult();
    
    // Step 2: Get field-specific errors (as opposed to class-level errors)
    List<FieldError> fieldErrors = bindingResult.getFieldErrors();
    
    // Step 3: Build a map of field → error message
    Map<String, String> errors = new HashMap<>();
    fieldErrors.forEach(fieldError -> {
        errors.put(
            fieldError.getField(),          // "email", "password", "cnrNumber"
            fieldError.getDefaultMessage()  // "Invalid email format", "must be at least 8 chars"
        );
    });
    
    // Step 4: Build and return the ErrorResponse
    ErrorResponse error = ErrorResponse.withFieldErrors(400, "Validation Failed",
        "One or more fields have errors", errors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
}
```

### What VaadVivaad actually does (more concise with forEach lambda)

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new HashMap<>();
    ex.getBindingResult().getFieldErrors().forEach(fieldError ->
        fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage())
    );
    log.warn("Validation failed: {}", fieldErrors);
    ErrorResponse error = ErrorResponse.withFieldErrors(
        HttpStatus.BAD_REQUEST.value(),
        "Validation Failed",
        "One or more fields have errors",
        fieldErrors
    );
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
}
```

### Multiple violations at once

If `RegisterRequest` has both bad email AND short password, `fieldErrors` will contain BOTH:

```json
{
  "fieldErrors": {
    "email": "Invalid email format",
    "password": "Password must be at least 8 characters"
  }
}
```

This is better UX than reporting one error at a time — the client gets all violations in one response.

### FieldError vs ObjectError

- `FieldError` — violation tied to a specific field (`email`, `password`)
- `ObjectError` — class-level violation (e.g., "password and confirmPassword must match" — a cross-field constraint)

`getFieldErrors()` returns only field-level errors. For class-level: `getBindingResult().getGlobalErrors()`.

---

## 13. Custom Validators

When built-in annotations aren't enough, you can create your own constraint annotation.

### The pattern

Two pieces: the annotation + the validator class.

```java
// Step 1: Define the annotation
@Documented
@Constraint(validatedBy = CnrNumberValidator.class)  // which class does the validation
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCnrNumber {
    String message() default "Invalid CNR number format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    // Required by JSR-380 spec — these 3 attributes are mandatory
}

// Step 2: Implement the validator
public class CnrNumberValidator implements ConstraintValidator<ValidCnrNumber, String> {

    // CNR format for Indian courts: TNCH01001234567 (typically 16-17 chars)
    private static final Pattern CNR_PATTERN = 
        Pattern.compile("^[A-Z]{4}[0-9]{2}[A-Z0-9]{9,15}$");

    @Override
    public void initialize(ValidCnrNumber annotation) {
        // can read annotation attributes here if needed
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false; // let @NotBlank handle null/blank separately
        }
        
        boolean matches = CNR_PATTERN.matcher(value).matches();
        
        if (!matches) {
            // Override the default message with a dynamic one
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                "CNR '" + value + "' is invalid. Format: TNCH010012345678"
            ).addConstraintViolation();
        }
        
        return matches;
    }
}
```

### Using the custom annotation

```java
// Instead of @Pattern on CnrLookupRequest:
public record CnrLookupRequest(
    @NotBlank(message = "CNR number is required")
    @ValidCnrNumber  // your custom annotation
    String cnrNumber
) {}
```

### Why VaadVivaad uses @Pattern instead of a custom validator

For CNR validation, the pattern is simple enough that `@Pattern` is sufficient. Custom validators are preferred when:
- The validation requires a DB lookup (e.g., check if email is already taken)
- Multiple fields need to be compared (cross-field validation)
- The logic is too complex for a single regex

For cross-field validation (e.g., password == confirmPassword), the annotation goes on the class:

```java
@PasswordsMatch  // class-level annotation
public record RegistrationRequest(
    String password,
    String confirmPassword
) {}
```

---

## 14. @PathVariable and @RequestParam Validation

Path variable and query parameter validation is more nuanced than body validation.

### The problem

```java
// This does NOT validate path variables automatically:
@GetMapping("/{id}")
public ResponseEntity<...> getCase(@PathVariable UUID id) { ... }
// If id is invalid UUID format, Spring throws a different exception (MethodArgumentTypeMismatchException)
```

### Validating @PathVariable with @Validated

To use constraint annotations on `@PathVariable` or `@RequestParam`, add `@Validated` at the controller class level:

```java
@RestController
@RequestMapping("/api/cases")
@Validated  // enables method-level validation for @PathVariable and @RequestParam
public class CaseLookupController {

    @GetMapping("/{pageNumber}")
    public ResponseEntity<...> getCases(
            @PathVariable @Min(value = 1, message = "Page number must be at least 1") int pageNumber,
            @RequestParam @Max(value = 100, message = "Page size cannot exceed 100") int pageSize) {
        // ...
    }
}
```

### The different exception type

Body validation (`@Valid @RequestBody`) → throws `MethodArgumentNotValidException`
Path/param validation (`@Validated` on class) → throws `ConstraintViolationException`

You need a separate handler for this:

```java
@ExceptionHandler(ConstraintViolationException.class)
public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
    Map<String, String> errors = new HashMap<>();
    ex.getConstraintViolations().forEach(cv -> {
        // path is like "getCases.pageNumber" — extract just "pageNumber"
        String field = cv.getPropertyPath().toString();
        errors.put(field, cv.getMessage());
    });
    ErrorResponse error = ErrorResponse.withFieldErrors(400, "Validation Failed",
        "One or more path/query parameters are invalid", errors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
}
```

VaadVivaad's current `CaseLookupController` doesn't use `@Validated` class-level — the `@PathVariable UUID id` relies on Spring's type conversion (invalid UUID format → `MethodArgumentTypeMismatchException`). This is a common pattern since UUID parsing itself acts as format validation.

---

## 15. HTTP Status Codes — Complete Practical Guide

### 2xx — Success

| Code | Name | When to use |
|---|---|---|
| 200 | OK | Standard success for GET, PUT, PATCH |
| 201 | Created | POST that creates a resource (`ResponseEntity.status(201)`) |
| 204 | No Content | DELETE success, or update with no body returned |
| 206 | Partial Content | Paginated/chunked responses |

### 3xx — Redirection

| Code | Name | When to use |
|---|---|---|
| 301 | Moved Permanently | Resource URL has permanently changed |
| 302 | Found | Temporary redirect |
| 304 | Not Modified | Caching — client's cached version is still valid |

### 4xx — Client Errors (the client did something wrong)

| Code | Name | When to use |
|---|---|---|
| 400 | Bad Request | Invalid request format, failed validation, missing required fields |
| 401 | Unauthorized | Not authenticated (no token, expired token, wrong password) |
| 403 | Forbidden | Authenticated but not authorized (right user, wrong permissions) |
| 404 | Not Found | Resource doesn't exist |
| 405 | Method Not Allowed | Wrong HTTP verb (POST on a GET-only endpoint) |
| 409 | Conflict | Request conflicts with server state (duplicate email, already exists) |
| 410 | Gone | Resource existed but was permanently deleted |
| 422 | Unprocessable Entity | Request is syntactically correct but semantically invalid |
| 429 | Too Many Requests | Rate limiting |

### 5xx — Server Errors (our fault)

| Code | Name | When to use |
|---|---|---|
| 500 | Internal Server Error | Unexpected failure (default catch-all) |
| 502 | Bad Gateway | Upstream service returned bad response |
| 503 | Service Unavailable | Server overloaded or down for maintenance |
| 504 | Gateway Timeout | Upstream service timed out |

### The tricky distinctions

**400 vs 422:**
- 400 — structurally malformed request (can't even parse it, missing required field)
- 422 — parseable but fails business rules (email is syntactically valid but domain is blocked)
- In practice, most APIs use 400 for both. VaadVivaad uses 400 for all validation failures.

**401 vs 403:**
- 401 — "Who are you? I don't know you." — Authentication failure (no/bad token)
- 403 — "I know who you are, but you can't do this." — Authorization failure (lacks permission)

**404 vs 409:**
- 404 — "That thing doesn't exist."
- 409 — "That thing already exists / conflicts with current state."

### How VaadVivaad maps exceptions to status codes

```
ResourceNotFoundException    → 404 Not Found
CnrValidationException       → 400 Bad Request
MethodArgumentNotValidException → 400 Bad Request
BadCredentialsException      → 401 Unauthorized
IllegalArgumentException     → 409 Conflict
Exception (catch-all)        → 500 Internal Server Error
```

---

## 16. ResponseEntity — Full Control Over HTTP Responses

`ResponseEntity<T>` is Spring's way to control the HTTP response completely: status code, headers, and body.

### Static factory methods

```java
// 200 OK with body
ResponseEntity.ok(body)
// equivalent to: ResponseEntity.status(200).body(body)

// 201 Created with body
ResponseEntity.status(HttpStatus.CREATED).body(body)

// 204 No Content (no body)
ResponseEntity.noContent().build()

// 404 Not Found with body
ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)

// 400 Bad Request with body
ResponseEntity.badRequest().body(errorResponse)
```

### Adding headers

```java
ResponseEntity<ApiResponse<AuthResponse>> response = ResponseEntity
    .status(HttpStatus.CREATED)
    .header("X-Request-Id", UUID.randomUUID().toString())
    .header("Location", "/api/users/" + newUser.id())
    .body(ApiResponse.success(newUser));
```

### Building ResponseEntity programmatically

```java
HttpHeaders headers = new HttpHeaders();
headers.add("X-Custom-Header", "value");

return new ResponseEntity<>(body, headers, HttpStatus.CREATED);
// or
return ResponseEntity.status(HttpStatus.CREATED)
    .headers(headers)
    .body(body);
```

### Why ResponseEntity over @ResponseStatus

You could annotate a method with `@ResponseStatus(HttpStatus.CREATED)` instead of wrapping in ResponseEntity. But `ResponseEntity` is preferred because:
- Status code can be conditional (e.g., 200 if found, 201 if created)
- You can add headers dynamically
- You can return null body (204) explicitly
- Explicit is better than annotated when reading code

---

## 17. Logging Exceptions Properly

### The rule: log level reflects expectedness

```java
// 4xx errors: use log.warn() — expected, known failure paths
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
    log.warn("Resource not found: {}", ex.getMessage());  // message only, no stack trace
    // ...
}

// 5xx errors: use log.error() WITH the exception — need full stack trace to debug
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
    log.error("Unexpected error: ", ex);  // the comma + ex = full stack trace in logs
    // ...
}
```

The difference: `log.error("message: ", ex)` vs `log.error("message: {}", ex.getMessage())`
- `, ex` — passes the Throwable as the last argument — SLF4J logs the full stack trace
- `{}, ex.getMessage()` — only logs the message string, no stack trace

### SLF4J parameterized logging

```java
// GOOD: parameterized logging (no string concatenation, lazy evaluation)
log.warn("CNR validation failed: {}", ex.getMessage());
log.warn("Validation failed: {}", fieldErrors);

// BAD: string concatenation (always executes even if log level is off)
log.warn("CNR validation failed: " + ex.getMessage()); // avoid this
```

### What NOT to log

```java
// NEVER log passwords, tokens, or PII:
log.info("Login attempt for user: {} with password: {}", email, password); // NEVER

// OK: log the fact, not the secret:
log.info("Login attempt for email: {}", email);

// NEVER log the BadCredentialsException details to clients:
// VaadVivaad returns "Invalid email or password" — not the actual cause
// This prevents revealing which field was wrong (email enumeration attack)
```

### MDC for request tracing (advanced)

```java
// MDC = Mapped Diagnostic Context — adds per-request context to all log lines
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear(); // always clear to avoid thread pool contamination
        }
    }
}
// logback pattern: "[%X{requestId}] %msg%n"
// All log lines for that request now include the same requestId
```

---

## 18. Node → Java Comparison

| Concept | Node/Express | Spring Boot |
|---|---|---|
| Central error handler | `app.use((err, req, res, next) => ...)` | `@RestControllerAdvice` class |
| Per-error-type handling | `if (err instanceof NotFoundError)` inside error middleware | `@ExceptionHandler(ResourceNotFoundException.class)` method |
| Passing error to handler | `next(err)` in try/catch | `throw new ResourceNotFoundException(...)` — propagates automatically |
| Request validation | Joi / Zod schema + validate middleware | JSR-380 annotations on DTO fields + `@Valid` on controller param |
| Custom error classes | `class NotFoundError extends Error { constructor() { super('...') } }` | `class ResourceNotFoundException extends RuntimeException` |
| Validation failure response | `res.status(400).json({ errors: [...] })` | `MethodArgumentNotValidException` → `GlobalExceptionHandler` → `ErrorResponse` |
| Response wrapper | Manual `{ success, data, message }` factory | `ApiResponse<T>` record with static factory methods |
| HTTP status codes | `res.status(404).json(...)` | `ResponseEntity.status(HttpStatus.NOT_FOUND).body(...)` |
| Logging | `console.error(err)` / Winston / Pino | SLF4J + Logback: `log.error("msg: ", ex)` |
| Type-safe errors | TypeScript discriminated unions or custom types | Java checked/unchecked exception hierarchy |
| Transaction rollback on error | Manual (no built-in concept) | `@Transactional` + unchecked exception → auto-rollback |
| Cascading validation | Nested Zod schemas | `@Valid` on nested objects triggers cascading validation |

---

## 19. Senior Interview Q&A

**Q1: What is the difference between @ControllerAdvice and @RestControllerAdvice?**

`@ControllerAdvice` is a component that applies cross-cutting behavior (exception handling, model attributes, data binding) to all controllers. It does not serialize return values to JSON by default — you'd need `@ResponseBody` on each handler method. `@RestControllerAdvice` is a composed annotation: `@ControllerAdvice + @ResponseBody`. It adds `@ResponseBody` to every handler method automatically, making it the right choice for REST APIs where all responses (including errors) are serialized as JSON.

---

**Q2: In VaadVivaad's GlobalExceptionHandler, why does the Exception.class handler use `log.error("Unexpected error: ", ex)` with a comma, while the ResourceNotFoundException handler uses `log.warn("Resource not found: {}", ex.getMessage())`?**

The comma + Throwable syntax in SLF4J (`log.error("msg: ", ex)`) tells the logging framework to append the full stack trace. This is critical for unexpected 500 errors — you need the complete stack trace to debug the root cause. For expected 4xx errors like ResourceNotFoundException, the message alone is sufficient information. Logging a full stack trace for every "resource not found" would flood the logs with noise and make real errors harder to find.

---

**Q3: Why do all VaadVivaad custom exceptions extend RuntimeException instead of Exception?**

Three reasons. First, unchecked exceptions propagate without forcing `throws` declarations on every method in the call chain — services and controllers stay clean. Second, Spring's `@Transactional` only rolls back transactions on unchecked exceptions by default, so throwing a RuntimeException subclass ensures data consistency. Third, in a web API, the HTTP client cannot "handle" a server-side exception — it only sees the HTTP response. There is no value in forcing try/catch at every layer; centralized handling in GlobalExceptionHandler is both cleaner and more maintainable.

---

**Q4: What is the difference between @Valid and @Validated?**

`@Valid` is a JSR-380 (Bean Validation) standard annotation that triggers validation on a method parameter and supports cascading validation into nested objects. `@Validated` is Spring's extension that adds support for validation groups (different constraint subsets for create vs update operations). Additionally, `@Validated` must be placed at the controller class level to enable validation of `@PathVariable` and `@RequestParam` parameters — `@Valid` cannot be used for this purpose. For standard REST API request body validation, `@Valid` is sufficient.

---

**Q5: Why does MethodArgumentNotValidException require different handling than ConstraintViolationException?**

`MethodArgumentNotValidException` is thrown when `@Valid @RequestBody` fails — it wraps a `BindingResult` containing field-level errors accessible via `getBindingResult().getFieldErrors()`. `ConstraintViolationException` is thrown when `@Validated` class-level path/param validation fails — it wraps a `Set<ConstraintViolation>`. They have different APIs, different sources, and require separate `@ExceptionHandler` methods. Treating them the same would cause one to fall through to the generic Exception handler, returning a misleading 500 instead of 400.

---

**Q6: Explain the `ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue)` constructor. Why have multiple constructors?**

The convenience constructor produces a standardized, human-readable message: "Case not found with cnrNumber: 'TNCH01001234567'" using String.format. This eliminates the need for every service to format the same message pattern. The single-argument constructor exists for cases where the message doesn't follow this pattern. Multiple constructors (constructor overloading) allow different call sites to choose the most appropriate form without duplication. This is the Java equivalent of having a factory function with multiple signatures.

---

**Q7: In VaadVivaad's BadCredentialsException handler, why does it always return "Invalid email or password" instead of "Invalid password" or "User not found"?**

This is a security measure against user enumeration attacks. If the API returned "User not found" for unknown emails and "Invalid password" for known emails, an attacker could probe the system to discover which email addresses are registered (e.g., to target phishing). By returning the same message regardless of which credential was wrong, the API reveals no information about which part failed. This is standard security practice for authentication endpoints.

---

**Q8: What happens if you have two @ExceptionHandler methods — one for AuthenticationException and one for BadCredentialsException — and BadCredentialsException extends AuthenticationException? Which handler runs?**

Spring picks the most specific handler — the one whose exception type is closest to the thrown exception in the inheritance hierarchy. `BadCredentialsException` extends `AuthenticationException`, so throwing `BadCredentialsException` triggers the `BadCredentialsException` handler, not the `AuthenticationException` handler. This allows fine-grained handling for specific exception types while the parent handler acts as a fallback for other subclasses.

---

**Q9: How would you add the request path to every ErrorResponse without manually passing HttpServletRequest to every @ExceptionHandler method?**

Spring's `@ControllerAdvice` supports injecting `HttpServletRequest` as a method parameter in `@ExceptionHandler` methods — as VaadVivaad does in the `IllegalArgumentException` handler with `request.getRequestURI()`. But for a more elegant approach, you could use a `RequestContextHolder` inside a factory method, or switch to Spring Boot 3's `ProblemDetail` which includes an `instance` field (the URI). Alternatively, a response-wrapping filter could add the path as a response header after the fact.

---

**Q10: VaadVivaad's CnrLookupRequest uses @Pattern for CNR format validation, but there's also a CnrValidationException thrown from the service layer. Why do both exist? Isn't that duplicate validation?**

They serve different purposes. The `@Pattern` annotation on the DTO is a syntactic check — is the format structurally correct (matches regex)? This runs before the controller method body, preventing invalid-format CNRs from ever reaching the service. `CnrValidationException` is thrown by the service for semantic business rule failures — for example, the CNR format is valid but it belongs to a jurisdiction this system doesn't support, or a scraper-specific business rule fails. Layered validation: DTO annotations gate format, service layer enforces business rules.

---

## 20. Senior Differentiators

What separates a senior developer's answer from a junior's on these topics:

### On exception handling architecture

**Junior:** "I put try/catch in every controller and return the error."

**Senior:** "Exception handling is a cross-cutting concern. Controllers should express happy-path intent — they are not the right place for error formatting logic. @RestControllerAdvice centralizes that concern, and each @ExceptionHandler method is a single-responsibility function that knows about one exception type and produces one HTTP response shape. The controller is pure orchestration."

---

### On checked vs unchecked

**Junior:** "RuntimeException means you don't have to catch it."

**Senior:** "Checked exceptions enforce a contract at compile time — callers must acknowledge the failure mode. In a layered web application, that creates massive coupling: service exceptions would have to be declared in every method signature up the call stack. Unchecked exceptions let the failure propagate transparently to the one place designed to handle it: the global exception handler. The @Transactional rollback behavior is also part of this — Spring's transaction management only auto-rolls back on RuntimeException by default, which is another reason services throw unchecked exceptions."

---

### On validation

**Junior:** "I added @NotBlank and @Valid to validate the request."

**Senior:** "JSR-380 validation on DTOs is syntactic — it checks structure and format. You still need business rule validation in the service layer. The separation is: can this input ever be valid? (DTO validation) vs is this input valid in the current context? (service validation). @Valid on @RequestBody means the controller method body is guaranteed to only execute with structurally valid input. Service layer throws domain exceptions for business constraint violations. Both layers contribute to a defense-in-depth strategy."

---

### On custom exceptions

**Junior:** "I throw new RuntimeException('User not found')."

**Senior:** "Stringly-typed exceptions make GlobalExceptionHandler impossible to maintain. If every failure is RuntimeException, you can't have different HTTP status codes and response messages for different failure modes. Custom exception classes provide type safety: ResourceNotFoundException always maps to 404, CnrValidationException always maps to 400. The exception class name itself is self-documenting. Additionally, the ResourceNotFoundException convenience constructor encodes a standard message format, eliminating formatting duplication across all the services that throw it."

---

### On ApiResponse and ErrorResponse

**Junior:** "I return different JSON shapes from different endpoints."

**Senior:** "A consistent response envelope is a contract with the frontend team and any API consumers. ApiResponse<T> means every success response has the same outer shape — the frontend can write one interceptor that handles all responses. Similarly, ErrorResponse means every error has status, error, message, fieldErrors, and timestamp — the frontend error handler is universal. The discipline of maintaining a consistent schema is what makes large APIs maintainable and allows contract-first development."

---

### On logging

**Junior:** "I log ex.getMessage() everywhere."

**Senior:** "The logging strategy should reflect operational needs. For expected failure modes (4xx), the message is sufficient — stack traces add noise without diagnostic value. For unexpected failures (5xx), the full stack trace is essential — `log.error('msg: ', ex)` passes the Throwable to SLF4J which appends the complete trace. Beyond that, I'd add MDC request correlation IDs so that all log lines for a single request share an ID, making distributed tracing possible. And never log PII or credentials — the BadCredentialsException handler intentionally returns a vague message and logs nothing, because the failed credential itself is sensitive."

---

*File: `09_exception_handling_validation.md` | Part of VaadVivaad Interview Prep Series*
