# Testing in Spring Boot — Senior-Level Guide
### Anchored to VaadVivaad (Spring Boot 3.4.4 / Java 21)

---

## The Mental Model Before Anything Else

In Node.js you probably test like this:

```js
// Jest unit test — pure function, no framework
test('should throw if user not found', () => {
  const repo = { findByEmail: jest.fn().mockResolvedValue(null) };
  const service = new AuthService(repo);
  await expect(service.login('x@x.com', 'pass')).rejects.toThrow('Not found');
});

// Supertest integration test — real Express app
it('GET /api/cases returns 200', async () => {
  const res = await request(app).get('/api/cases');
  expect(res.status).toBe(200);
});
```

In Spring Boot the same mental model applies — you just have more levers to control
*how much of Spring's infrastructure loads*, because Spring is heavier than Express.

The core question you answer before writing every test:
**"Do I need Spring's ApplicationContext for this test, or can I work without it?"**

| Need Spring? | What to use | Speed |
|---|---|---|
| No — pure logic (parser, util, mapper) | Plain `new ClassName()` | Instant |
| No — but I need mocks injected | `@ExtendWith(MockitoExtension.class)` | ~50ms |
| Yes — web layer only (controller, filter) | `@WebMvcTest` | ~3s |
| Yes — JPA layer only (repository, SQL) | `@DataJpaTest` | ~5s |
| Yes — everything (full wiring test) | `@SpringBootTest` | 10-30s |

**The rule:** use the smallest slice that proves what you want to prove.

---

## 1. The Testing Pyramid

```
           /\
          /E2E\          <- Few, slow, expensive (Playwright, RestAssured full stack)
         /------\
        / Integr.\      <- Some, medium speed (@WebMvcTest, @DataJpaTest)
       /----------\
      /  Unit Tests \   <- Many, instant (Mockito, pure Java)
     /--------------\
```

### In VaadVivaad terms:

**Unit tests** (no Spring context):
- `ECourtHtmlParserTest` — the one you already have. Parser is pure Java + Jsoup.
  Instantiated with `new ECourtHtmlParser()`. Runs in milliseconds.
- `AuthServiceTest` — mock `UserRepository`, `JwtService`, etc. Test the decision
  logic in `register()` and `login()`.

**Integration/slice tests** (partial Spring context):
- `@WebMvcTest(CaseLookupController.class)` — loads controller + security.
  Mock `CaseLookupService` with `@MockBean`. Use `MockMvc` to hit endpoints.
- `@DataJpaTest` — loads JPA, H2 in-memory. Test `findByCnrNumber()` with
  real SQL. No service, no controller.

**Full integration tests**:
- `VaadVivaadApplicationTests` — the `contextLoads()` test. Just proves the
  application starts without misconfiguration. No test logic needed.

**E2E** (not in the project yet):
- Real HTTP calls against a running server (RANDOM_PORT mode).
- Rarely used in CI — reserved for smoke tests post-deployment.

### Node analogy:

| Node/Jest | Spring Boot |
|---|---|
| `jest.fn()` mocked service, pure test | Mockito `@Mock` + `@InjectMocks` |
| `supertest(app)` with mocked DB | `@WebMvcTest` + `@MockBean` service |
| Real DB test with `pg` in `beforeAll` | `@DataJpaTest` with H2 or Testcontainers |
| `docker-compose up` before test suite | `@Testcontainers` + `@Container` |

---

## 2. JUnit 5 — The Test Runner

JUnit 5 is what executes your tests. Think of it as Jest's runner (`jest`), not the assertion library.

### Core annotations

```java
import org.junit.jupiter.api.*;

class CaseLookupServiceTest {

    @BeforeAll          // Runs once before ALL tests in this class — static method
    static void initOnce() { }

    @BeforeEach         // Runs before EACH test — like Jest's beforeEach()
    void setUp() { }

    @AfterEach          // Runs after EACH test — Jest's afterEach()
    void tearDown() { }

    @AfterAll           // Runs once after ALL tests — static method
    static void cleanupOnce() { }

    @Test               // A test method — like Jest's it() or test()
    void lookupByCnr_whenFound_returnsResponse() { }

    @DisplayName("CNR lookup: case not found → ResourceNotFoundException")
    @Test
    void lookupByCnr_whenNotFound_throwsException() { }
}
```

**Naming convention for test methods** (use one style consistently):
```
methodName_givenCondition_expectedOutcome
// Examples:
lookupByCnr_whenFound_returnsCaseResponse()
register_withDuplicateEmail_throwsIllegalArgumentException()
parse_withEmptyHtml_throwsScraperException()
```

This reads like documentation. A failing test tells you immediately what broke.

### @Nested — grouping tests logically

```java
// Jest equivalent: nested describe() blocks
class AuthServiceTest {

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        void happyPath_createsUserAndReturnsToken() { }

        @Test
        void withDuplicateEmail_throwsIllegalArgumentException() { }

        @Test
        void withNullRole_defaultsToUSER() { }
    }

    @Nested
    @DisplayName("login()")
    class Login {

        @Test
        void withValidCredentials_returnsAuthResponse() { }

        @Test
        void withUnknownEmail_throwsResourceNotFoundException() { }
    }
}
```

### @ParameterizedTest — data-driven tests

```java
// Instead of writing 5 identical tests with different CNR formats:
@ParameterizedTest
@ValueSource(strings = { "", "  ", "INVALID", "ABC123", "toolongstring12345678901" })
void lookupByCnr_withInvalidCnrFormat_throwsValidationException(String invalidCnr) {
    assertThatThrownBy(() -> service.lookupByCnr(invalidCnr))
        .isInstanceOf(CnrValidationException.class);
}

// With multiple arguments:
@ParameterizedTest
@CsvSource({
    "USER,   false",
    "ADMIN,  true",
    "LAWYER, true"
})
void canAccessAdminPanel(String role, boolean expectedAccess) {
    // test role-based access
}
```

### Node → JUnit 5 mapping

| Jest | JUnit 5 |
|---|---|
| `describe('group', () => {})` | `@Nested class Group {}` |
| `it('test name', () => {})` | `@Test @DisplayName("test name")` |
| `beforeEach(() => {})` | `@BeforeEach void setUp()` |
| `beforeAll(() => {})` | `@BeforeAll static void initOnce()` |
| `test.each(data)(fn)` | `@ParameterizedTest @CsvSource(...)` |

---

## 3. AssertJ — Fluent Assertions

AssertJ ships with `spring-boot-starter-test`. It's Java's answer to Jest's `expect()`,
but more chainable and more readable than JUnit5's built-in `assertEquals()`.

```java
import static org.assertj.core.api.Assertions.*;

// Instead of JUnit5's verbose form:
assertEquals("TNMD030056782023", result.cnrNumber());   // old-style, fails: "expected X but was Y"
assertNotNull(result.hearings());
assertEquals(3, result.hearings().size());

// AssertJ's fluent form — reads like English, better error messages:
assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");
assertThat(result.hearings()).isNotNull().hasSize(3);

// Chaining:
assertThat(result)
    .isNotNull()
    .extracting(CaseResponse::cnrNumber, CaseResponse::status)
    .containsExactly("TNMD030056782023", "PENDING");

// Collections:
assertThat(result.hearings())
    .hasSize(3)
    .extracting(HearingResponse::purpose)
    .containsExactlyInAnyOrder("Admission", "Arguments", "Judgment");

// Exceptions (the right way):
assertThatThrownBy(() -> service.lookupByCnr("NONEXISTENT"))
    .isInstanceOf(ResourceNotFoundException.class)
    .hasMessageContaining("Court case")
    .hasMessageContaining("NONEXISTENT");

// When you only care about exception type:
assertThatExceptionOfType(ResourceNotFoundException.class)
    .isThrownBy(() -> service.lookupByCnr("BAD"));

// Soft assertions — collect ALL failures, not just first:
SoftAssertions.assertSoftly(soft -> {
    soft.assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");
    soft.assertThat(result.status()).isEqualTo("PENDING");
    soft.assertThat(result.petitioner()).isEqualTo("Ravi Kumar");
});
```

### From the existing ECourtHtmlParserTest:

```java
// VaadVivaad uses AssertJ correctly — notice the chain:
assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");
assertThat(result.hearings()).hasSize(3);

// And the exception pattern:
assertThatThrownBy(() -> parser.parse(""))
    .isInstanceOf(ScraperException.class)
    .hasMessageContaining("Empty HTML");
```

---

## 4. Mockito — Java's Mocking Library

Mockito is Jest's `jest.mock()` + `jest.fn()` combined.

### The three core concepts

**`@Mock`** — creates a fake version of a class. All methods return default values
(null, 0, false, empty list) unless you stub them. No real code runs.

**`@InjectMocks`** — creates a real instance of your class-under-test and injects
all `@Mock` fields into it via constructor/setter/field injection.

**`@Spy`** — wraps a real object. Real code runs unless you explicitly stub a method.
Use sparingly — usually a sign your design needs work.

```java
// Jest equivalent:
const mockRepo = {
  findByCnrNumber: jest.fn(),
  existsByCnrNumber: jest.fn(),
};
const service = new CaseLookupService(mockRepo);

// Java equivalent:
@ExtendWith(MockitoExtension.class)
class CaseLookupServiceTest {

    @Mock
    CourtCaseRepository courtCaseRepository;  // fake — does nothing by default

    @Mock
    HearingRepository hearingRepository;

    @InjectMocks
    CaseLookupService caseLookupService;      // real service, mocks injected into it
}
```

### Stubbing — telling the mock what to return

```java
// Jest: mockRepo.findByCnrNumber.mockReturnValue(Promise.resolve(mockCase))
// Java:
when(courtCaseRepository.findByCnrNumber("TNMD030056782023"))
    .thenReturn(Optional.of(mockCourtCase));

// Throw exception:
when(courtCaseRepository.findByCnrNumber("BAD_CNR"))
    .thenReturn(Optional.empty());
// (service will then throw ResourceNotFoundException)

// Throw directly from the mock:
when(courtCaseRepository.save(any()))
    .thenThrow(new DataIntegrityViolationException("duplicate key"));

// For void methods:
doThrow(new RuntimeException("DB down"))
    .when(courtCaseRepository).deleteById(any());

// Return different values on subsequent calls:
when(repo.findByEmail("x@x.com"))
    .thenReturn(Optional.empty())     // first call
    .thenReturn(Optional.of(user));   // second call
```

### Verification — did the mock get called?

```java
// Jest: expect(mockRepo.findByCnrNumber).toHaveBeenCalledWith("TNMD...")
// Java:
verify(courtCaseRepository).findByCnrNumber("TNMD030056782023");
verify(courtCaseRepository, times(1)).save(any(CourtCase.class));
verify(hearingRepository, never()).deleteAll();       // was NOT called
verify(courtCaseRepository, atLeastOnce()).existsByCnrNumber(any());

// Capture the argument that was passed to the mock:
ArgumentCaptor<CourtCase> captor = ArgumentCaptor.forClass(CourtCase.class);
verify(courtCaseRepository).save(captor.capture());
CourtCase saved = captor.getValue();
assertThat(saved.getCnrNumber()).isEqualTo("TNMD030056782023");
assertThat(saved.getStatus()).isEqualTo(CaseStatus.PENDING);
```

### Argument matchers

```java
// any() — match any argument of the right type
when(repo.save(any(CourtCase.class))).thenReturn(savedCase);

// anyString(), anyInt(), anyList(), anyMap()
when(jwtService.generateToken(anyString())).thenReturn("mock.jwt.token");

// eq() — when you need exact value alongside matchers
verify(repo).findByEmail(eq("test@test.com"));

// Custom matcher with argThat:
verify(repo).save(argThat(c -> c.getCnrNumber().startsWith("TNMD")));
```

---

## 5. @ExtendWith(MockitoExtension.class) — Pure Unit Tests

This annotation tells JUnit5: "activate Mockito's annotation processing for this test class."
No Spring context. No beans. No database. No network. Pure Java.

```java
// Jest equivalent: a plain describe() block with jest.fn() mocks
// Java equivalent:
@ExtendWith(MockitoExtension.class)
class CaseLookupServiceTest {

    @Mock CourtCaseRepository courtCaseRepository;
    @Mock HearingRepository hearingRepository;

    @InjectMocks CaseLookupService caseLookupService;

    // Tests run in ~50ms total
}
```

**Why this matters in interviews:** Interviewers want to see that you know
the difference between `@Mock` (Mockito-only) and `@MockBean` (Spring-aware).
Using `@MockBean` when you don't need Spring is a red flag — it starts the context,
adding 5-30 seconds to your test suite for no benefit.

---

## 6. Unit Testing a Service — Complete VaadVivaad Example

### Testing CaseLookupService

```java
package com.vaadvivaad.lookup.service;

import com.vaadvivaad.common.exception.CnrValidationException;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import com.vaadvivaad.lookup.dto.CaseResponse;
import com.vaadvivaad.lookup.dto.CreateCaseRequest;
import com.vaadvivaad.lookup.entity.CaseStatus;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.repository.CourtCaseRepository;
import com.vaadvivaad.lookup.repository.HearingRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CaseLookupService")
class CaseLookupServiceTest {

    @Mock
    CourtCaseRepository courtCaseRepository;

    @Mock
    HearingRepository hearingRepository;

    @InjectMocks
    CaseLookupService caseLookupService;

    // -- Test fixtures --
    private CourtCase sampleCase;
    private Hearing sampleHearing;

    @BeforeEach
    void setUp() {
        sampleCase = new CourtCase();
        sampleCase.setId(UUID.randomUUID());
        sampleCase.setCnrNumber("TNMD030056782023");
        sampleCase.setCaseType("Civil Suit");
        sampleCase.setFilingDate(LocalDate.of(2023, 3, 15));
        sampleCase.setStatus(CaseStatus.PENDING);
        sampleCase.setPetitioner("Ravi Kumar");
        sampleCase.setRespondent("State of Tamil Nadu");
        sampleCase.setCourtName("Principal District Court, Chennai");
        sampleCase.setJudgeName("Hon. Justice Krishnamurthy");

        sampleHearing = new Hearing();
        sampleHearing.setId(UUID.randomUUID());
        sampleHearing.setHearingDate(LocalDate.of(2023, 5, 10));
        sampleHearing.setPurpose("Admission");
        sampleHearing.setNotes("Summons issued");
        sampleHearing.setCourtCase(sampleCase);
    }

    @Nested
    @DisplayName("lookupByCnr()")
    class LookupByCnr {

        @Test
        @DisplayName("happy path: returns CaseResponse with hearings")
        void whenFound_returnsCaseResponse() {
            // Arrange — "given"
            when(courtCaseRepository.findByCnrNumber("TNMD030056782023"))
                .thenReturn(Optional.of(sampleCase));
            when(hearingRepository.findByCourtCaseIdOrderByHearingDateDesc(sampleCase.getId()))
                .thenReturn(List.of(sampleHearing));

            // Act — "when"
            CaseResponse result = caseLookupService.lookupByCnr("TNMD030056782023");

            // Assert — "then"
            assertThat(result).isNotNull();
            assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");
            assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.hearings()).hasSize(1);
            assertThat(result.hearings().get(0).purpose()).isEqualTo("Admission");

            // Verify interactions
            verify(courtCaseRepository).findByCnrNumber("TNMD030056782023");
            verify(hearingRepository).findByCourtCaseIdOrderByHearingDateDesc(sampleCase.getId());
        }

        @Test
        @DisplayName("not found: throws ResourceNotFoundException")
        void whenNotFound_throwsResourceNotFoundException() {
            // Arrange
            when(courtCaseRepository.findByCnrNumber("NONEXISTENT"))
                .thenReturn(Optional.empty());

            // Act + Assert — one step for exception tests
            assertThatThrownBy(() -> caseLookupService.lookupByCnr("NONEXISTENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Court case")
                .hasMessageContaining("NONEXISTENT");

            // The hearing repo should NEVER be called if case not found
            verifyNoInteractions(hearingRepository);
        }
    }

    @Nested
    @DisplayName("createCase()")
    class CreateCase {

        @Test
        @DisplayName("happy path: saves case and returns response")
        void withNewCnr_savesAndReturnsResponse() {
            // Arrange
            CreateCaseRequest request = new CreateCaseRequest(
                "TNMD030056782023", "Civil Suit", "CS/1234/2023",
                LocalDate.of(2023, 3, 15), null, null,
                "Ravi Kumar", "State of Tamil Nadu",
                "Principal District Court, Chennai",
                "Hon. Justice Krishnamurthy", null
            );

            when(courtCaseRepository.existsByCnrNumber("TNMD030056782023"))
                .thenReturn(false);
            when(courtCaseRepository.save(any(CourtCase.class)))
                .thenReturn(sampleCase);

            // Act
            CaseResponse result = caseLookupService.createCase(request);

            // Assert
            assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");

            // Capture what was actually saved — verify the entity was built correctly
            ArgumentCaptor<CourtCase> captor = ArgumentCaptor.forClass(CourtCase.class);
            verify(courtCaseRepository).save(captor.capture());
            CourtCase savedEntity = captor.getValue();
            assertThat(savedEntity.getCnrNumber()).isEqualTo("TNMD030056782023");
            assertThat(savedEntity.getStatus()).isEqualTo(CaseStatus.PENDING);
        }

        @Test
        @DisplayName("duplicate CNR: throws CnrValidationException")
        void withDuplicateCnr_throwsCnrValidationException() {
            // Arrange
            CreateCaseRequest request = new CreateCaseRequest(
                "TNMD030056782023", "Civil Suit", "CS/1234/2023",
                LocalDate.of(2023, 3, 15), null, null,
                "Ravi Kumar", "State of Tamil Nadu", "Chennai HC", "Judge", null
            );

            when(courtCaseRepository.existsByCnrNumber("TNMD030056782023"))
                .thenReturn(true);  // <-- already exists

            // Act + Assert
            assertThatThrownBy(() -> caseLookupService.createCase(request))
                .isInstanceOf(CnrValidationException.class)
                .hasMessageContaining("TNMD030056782023");

            // Save should NEVER be called when duplicate exists
            verify(courtCaseRepository, never()).save(any());
        }
    }
}
```

### Testing AuthService

```java
package com.vaadvivaad.auth.service;

import com.vaadvivaad.auth.dto.AuthResponse;
import com.vaadvivaad.auth.dto.LoginRequest;
import com.vaadvivaad.auth.dto.RegisterRequest;
import com.vaadvivaad.auth.security.JwtService;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import com.vaadvivaad.user.entity.Role;
import com.vaadvivaad.user.entity.User;
import com.vaadvivaad.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AuthenticationManager authenticationManager;

    @InjectMocks AuthService authService;

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("new email: hashes password, saves user, returns JWT")
        void withNewEmail_savesUserAndReturnsToken() {
            // Arrange
            RegisterRequest request = new RegisterRequest(
                "Ravi Kumar", "ravi@test.com", "password123", "9876543210", null
            );

            User savedUser = new User();
            savedUser.setId(UUID.randomUUID());
            savedUser.setEmail("ravi@test.com");
            savedUser.setFullName("Ravi Kumar");
            savedUser.setRole(Role.USER);
            savedUser.setPasswordHash("$2a$encoded");

            when(userRepository.existsByEmail("ravi@test.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("$2a$encoded");
            when(userRepository.save(any(User.class))).thenReturn(savedUser);
            when(jwtService.generateToken(any())).thenReturn("mock.jwt.token");

            // Act
            AuthResponse response = authService.register(request);

            // Assert
            assertThat(response.token()).isEqualTo("mock.jwt.token");
            assertThat(response.email()).isEqualTo("ravi@test.com");
            assertThat(response.role()).isEqualTo("USER");
            assertThat(response.message()).isEqualTo("Registration successful");

            // Password should be ENCODED, not stored in plain text
            verify(passwordEncoder).encode("password123");
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("duplicate email: throws IllegalArgumentException, never saves")
        void withDuplicateEmail_throwsAndNeverSaves() {
            RegisterRequest request = new RegisterRequest(
                "Ravi Kumar", "ravi@test.com", "password123", null, null
            );

            when(userRepository.existsByEmail("ravi@test.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ravi@test.com");

            verify(userRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);  // encode should never be called either
            verifyNoInteractions(jwtService);
        }

        @Test
        @DisplayName("null role in request: defaults to USER")
        void withNullRole_defaultsToUserRole() {
            RegisterRequest request = new RegisterRequest(
                "Ravi Kumar", "ravi@test.com", "password123", null, null // role = null
            );

            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(jwtService.generateToken(any())).thenReturn("token");

            authService.register(request);

            // Capture saved user and verify role defaults to USER
            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("invalid role string: silently defaults to USER")
        void withInvalidRoleString_defaultsToUserRole() {
            RegisterRequest request = new RegisterRequest(
                "Ravi Kumar", "ravi@test.com", "password123", null, "SUPERADMIN" // invalid
            );

            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(jwtService.generateToken(any())).thenReturn("token");

            authService.register(request);

            var captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(Role.USER);
        }
    }

    @Nested
    @DisplayName("login()")
    class Login {

        @Test
        @DisplayName("valid credentials: authenticates, finds user, returns JWT")
        void withValidCredentials_returnsAuthResponse() {
            LoginRequest request = new LoginRequest("ravi@test.com", "password123");

            User user = new User();
            user.setEmail("ravi@test.com");
            user.setPasswordHash("$2a$encoded");
            user.setRole(Role.USER);

            // authenticationManager.authenticate() — void return, do nothing by default
            when(userRepository.findByEmail("ravi@test.com")).thenReturn(Optional.of(user));
            when(jwtService.generateToken(any())).thenReturn("mock.jwt.token");

            AuthResponse response = authService.login(request);

            assertThat(response.token()).isEqualTo("mock.jwt.token");
            assertThat(response.email()).isEqualTo("ravi@test.com");
            assertThat(response.message()).isEqualTo("Login successful");

            // Verify authentication was called (delegated to Spring Security)
            verify(authenticationManager).authenticate(
                any(UsernamePasswordAuthenticationToken.class)
            );
        }

        @Test
        @DisplayName("wrong password: authManager throws BadCredentialsException")
        void withWrongPassword_throwsBadCredentialsException() {
            LoginRequest request = new LoginRequest("ravi@test.com", "wrongpass");

            doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any());

            assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

            // If auth fails, user lookup should never happen
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("auth passes but user vanished from DB: throws ResourceNotFoundException")
        void withAuthOkButUserGone_throwsResourceNotFoundException() {
            // Edge case: auth passed but the user was deleted between auth and lookup
            LoginRequest request = new LoginRequest("ghost@test.com", "password123");

            // authManager passes (no exception)
            when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
```

---

## 7. @SpringBootTest — Full Context Integration Tests

`@SpringBootTest` loads the **entire** Spring ApplicationContext — all beans, all
configurations, all auto-configurations. It's the equivalent of starting your whole
application, minus actual network traffic (unless you configure `RANDOM_PORT`).

```java
@SpringBootTest                     // Loads full context
@ActiveProfiles("dev")              // Use dev profile (so test DB config applies)
class VaadVivaadApplicationTests {

    @Test
    void contextLoads() {
        // If any bean misconfiguration exists, this test fails
        // It's your "smoke test" for wiring
    }
}
```

### webEnvironment options

```java
// Default: no server, no MockMvc auto-config
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)

// Mocked servlet environment — use with MockMvc (injected via @Autowired)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)

// Real server on a random available port — use with TestRestTemplate or WebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @LocalServerPort
    int port;

    @Test
    void healthCheckReturns200() {
        ResponseEntity<String> response =
            restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
```

**When to use `@SpringBootTest`:** When you need to verify that multiple beans
actually wire together correctly. Not for testing business logic — use
`@ExtendWith(MockitoExtension.class)` for that.

---

## 8. @WebMvcTest — Controller Slice Tests

`@WebMvcTest` loads ONLY the web layer: controllers, filters, security configuration,
argument resolvers. No service beans, no repositories, no DB.

This is the right test for: "does this endpoint return the right HTTP status?
Does validation reject bad input? Does security block unauthenticated requests?"

```java
package com.vaadvivaad.lookup.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.lookup.dto.CaseResponse;
import com.vaadvivaad.lookup.dto.CnrLookupRequest;
import com.vaadvivaad.lookup.service.CaseLookupService;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CaseLookupController.class)   // ONLY loads CaseLookupController + web layer
class CaseLookupControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // @MockBean replaces the real CaseLookupService bean in the Spring context
    // This is different from @Mock — it registers into the ApplicationContext
    @MockBean
    CaseLookupService caseLookupService;

    private CaseResponse sampleResponse;

    @BeforeEach
    void setUp() {
        sampleResponse = new CaseResponse(
            UUID.randomUUID(),
            "TNMD030056782023",
            "Civil Suit",
            "CS/1234/2023",
            LocalDate.of(2023, 3, 15),
            null, null,
            "PENDING",
            "Ravi Kumar",
            "State of Tamil Nadu",
            "Principal District Court, Chennai",
            "Hon. Justice Krishnamurthy",
            null,
            List.of()
        );
    }

    @Nested
    @DisplayName("POST /api/cases/lookup")
    class LookupByCnr {

        @Test
        @DisplayName("valid CNR with auth: returns 200 and case data")
        @WithMockUser  // Simulates an authenticated user in Spring Security context
        void withValidCnr_returns200() throws Exception {
            when(caseLookupService.lookupByCnr("TNMD030056782023"))
                .thenReturn(sampleResponse);

            CnrLookupRequest request = new CnrLookupRequest("TNMD030056782023");

            mockMvc.perform(post("/api/cases/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))  // CSRF token required for POST in Spring Security tests
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cnrNumber").value("TNMD030056782023"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.petitioner").value("Ravi Kumar"))
                .andExpect(jsonPath("$.message").value("Case found"));
        }

        @Test
        @DisplayName("unauthenticated request: returns 401")
        void withoutAuth_returns401() throws Exception {
            CnrLookupRequest request = new CnrLookupRequest("TNMD030056782023");

            mockMvc.perform(post("/api/cases/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

            // Service should never be called for unauthenticated requests
            verifyNoInteractions(caseLookupService);
        }

        @Test
        @DisplayName("case not found: returns 404")
        @WithMockUser
        void whenServiceThrowsNotFound_returns404() throws Exception {
            when(caseLookupService.lookupByCnr("NONEXISTENT"))
                .thenThrow(new ResourceNotFoundException("Court case", "cnrNumber", "NONEXISTENT"));

            CnrLookupRequest request = new CnrLookupRequest("NONEXISTENT");

            mockMvc.perform(post("/api/cases/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
                    .with(csrf()))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/cases/{id}")
    class GetById {

        @Test
        @DisplayName("valid UUID: returns 200")
        @WithMockUser
        void withValidId_returns200() throws Exception {
            UUID id = sampleResponse.id();
            when(caseLookupService.lookupById(id)).thenReturn(sampleResponse);

            mockMvc.perform(get("/api/cases/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
        }
    }
}
```

---

## 9. MockMvc — HTTP Without a Server

MockMvc is Spring's answer to supertest. It performs HTTP requests against your
controllers in-process — no network, no actual server startup.

```java
// supertest (Node):
const res = await request(app)
  .post('/api/cases/lookup')
  .set('Authorization', `Bearer ${token}`)
  .send({ cnrNumber: 'TNMD030056782023' });
expect(res.status).toBe(200);
expect(res.body.data.cnrNumber).toBe('TNMD030056782023');

// MockMvc (Java):
mockMvc.perform(
    post("/api/cases/lookup")
        .header("Authorization", "Bearer " + jwtToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"cnrNumber\": \"TNMD030056782023\"}")
)
.andExpect(status().isOk())
.andExpect(jsonPath("$.data.cnrNumber").value("TNMD030056782023"))
.andDo(print());  // prints the full request/response to console — useful for debugging
```

### Key MockMvc assertions

```java
// HTTP status
.andExpect(status().isOk())           // 200
.andExpect(status().isCreated())      // 201
.andExpect(status().isBadRequest())   // 400
.andExpect(status().isUnauthorized()) // 401
.andExpect(status().isNotFound())     // 404

// JSON body — uses JsonPath syntax (like XPath but for JSON)
.andExpect(jsonPath("$.message").value("Case found"))
.andExpect(jsonPath("$.data.cnrNumber").value("TNMD030056782023"))
.andExpect(jsonPath("$.data.hearings").isArray())
.andExpect(jsonPath("$.data.hearings.length()").value(3))
.andExpect(jsonPath("$.data.hearings[0].purpose").value("Admission"))

// Headers
.andExpect(header().string("Content-Type", "application/json"))

// Capture result for further inspection:
MvcResult result = mockMvc.perform(get("/api/cases"))
    .andExpect(status().isOk())
    .andReturn();

String body = result.getResponse().getContentAsString();
// then deserialize with ObjectMapper if needed
```

### JsonPath quick reference

```
$.message                   → root level field "message"
$.data.cnrNumber            → nested field
$.data.hearings             → array
$.data.hearings[0].purpose  → first element of array
$.data.hearings.length()    → array length
```

---

## 10. @DataJpaTest — Repository Slice Tests

`@DataJpaTest` loads only the JPA layer — entities, repositories, Flyway/Liquibase
(or H2 schema creation). No controllers, no services, no security.

By default it uses H2 in-memory database. It also wraps each test in a transaction
and rolls back after, so tests don't leave dirty data.

```java
package com.vaadvivaad.lookup.repository;

import com.vaadvivaad.lookup.entity.CaseStatus;
import com.vaadvivaad.lookup.entity.CourtCase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@DisplayName("CourtCaseRepository")
class CourtCaseRepositoryTest {

    @Autowired
    TestEntityManager entityManager;   // Helper for saving test data without using the repo under test

    @Autowired
    CourtCaseRepository courtCaseRepository;

    @BeforeEach
    void insertTestData() {
        CourtCase courtCase = new CourtCase();
        courtCase.setCnrNumber("TNMD030056782023");
        courtCase.setCaseType("Civil Suit");
        courtCase.setFilingDate(LocalDate.of(2023, 3, 15));
        courtCase.setStatus(CaseStatus.PENDING);
        courtCase.setPetitioner("Ravi Kumar");
        courtCase.setRespondent("State of Tamil Nadu");

        entityManager.persistAndFlush(courtCase);  // writes to H2, flushes SQL
    }

    @Test
    @DisplayName("findByCnrNumber: existing CNR returns the case")
    void findByCnrNumber_whenExists_returnsCase() {
        Optional<CourtCase> result = courtCaseRepository.findByCnrNumber("TNMD030056782023");

        assertThat(result).isPresent();
        assertThat(result.get().getCnrNumber()).isEqualTo("TNMD030056782023");
        assertThat(result.get().getStatus()).isEqualTo(CaseStatus.PENDING);
    }

    @Test
    @DisplayName("findByCnrNumber: unknown CNR returns empty Optional")
    void findByCnrNumber_whenNotExists_returnsEmpty() {
        Optional<CourtCase> result = courtCaseRepository.findByCnrNumber("NONEXISTENT_CNR");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("existsByCnrNumber: existing CNR returns true")
    void existsByCnrNumber_whenExists_returnsTrue() {
        boolean exists = courtCaseRepository.existsByCnrNumber("TNMD030056782023");

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByCnrNumber: unknown CNR returns false")
    void existsByCnrNumber_whenNotExists_returnsFalse() {
        assertThat(courtCaseRepository.existsByCnrNumber("GHOST_CNR")).isFalse();
    }
}
```

### Testing UserRepository

```java
@DataJpaTest
class UserRepositoryTest {

    @Autowired TestEntityManager entityManager;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void insertUser() {
        User user = new User();
        user.setEmail("ravi@test.com");
        user.setPasswordHash("$2a$encoded");
        user.setFullName("Ravi Kumar");
        user.setRole(Role.USER);
        entityManager.persistAndFlush(user);
    }

    @Test
    void findByEmail_whenExists_returnsUser() {
        Optional<User> result = userRepository.findByEmail("ravi@test.com");

        assertThat(result).isPresent();
        assertThat(result.get().getFullName()).isEqualTo("Ravi Kumar");
        assertThat(result.get().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void existsByEmail_whenExists_returnsTrue() {
        assertThat(userRepository.existsByEmail("ravi@test.com")).isTrue();
    }

    @Test
    void existsByEmail_whenNotExists_returnsFalse() {
        assertThat(userRepository.existsByEmail("ghost@test.com")).isFalse();
    }
}
```

**Important caveat:** `@DataJpaTest` uses H2 by default. If you use PostgreSQL-specific
SQL (e.g., `::jsonb` casting, `gen_random_uuid()`, Flyway migrations with PG-specific
syntax), H2 will fail. The fix: use Testcontainers with a real PostgreSQL.

---

## 11. @MockBean vs @Mock — Don't Confuse These

This is a common interview trap.

| | `@Mock` | `@MockBean` |
|---|---|---|
| From | Mockito | Spring Test |
| Works with | `@ExtendWith(MockitoExtension.class)` | `@WebMvcTest`, `@SpringBootTest` |
| Spring context? | No — pure Mockito | Yes — registers mock AS a Spring bean |
| Use when | Unit testing service/utility | Controller test needs mocked service |
| Cost | None — no Spring startup | Spring context starts (but faster with slices) |

```java
// WRONG: @Mock in a @WebMvcTest — the controller gets the real service, not your mock
@WebMvcTest(CaseLookupController.class)
class Wrong {
    @Mock CaseLookupService caseLookupService;  // Not injected into Spring context!
}

// RIGHT: @MockBean replaces the bean IN the Spring context
@WebMvcTest(CaseLookupController.class)
class Right {
    @MockBean CaseLookupService caseLookupService;  // Controller gets this mock
}

// ALSO RIGHT: pure Mockito test — no Spring at all
@ExtendWith(MockitoExtension.class)
class AlsoRight {
    @Mock CourtCaseRepository courtCaseRepository;
    @InjectMocks CaseLookupService caseLookupService;
}
```

**Mental model:** `@Mock` is a Mockito concept. `@MockBean` is Spring's way of saying
"take this Mockito mock and put it in my bean registry so that wired beans get it."

---

## 12. Testcontainers — Real Database in Tests

Testcontainers spins up a real PostgreSQL (or RabbitMQ, Redis) in Docker during your
test run. This eliminates the H2 dialect mismatch problem entirely.

**VaadVivaad currently does NOT have Testcontainers in pom.xml.** Here is how to add it:

```xml
<!-- Add to pom.xml dependencies section -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>

<!-- Spring Boot manages Testcontainers version via BOM — no version needed if using SB 3.1+ -->
```

### Usage example

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // Don't replace with H2
class CourtCaseRepositoryTestContainerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vaadvivaad_test")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CourtCaseRepository courtCaseRepository;

    @Test
    void findByCnrNumber_withRealPostgres_works() {
        CourtCase courtCase = new CourtCase();
        courtCase.setCnrNumber("TNMD030056782023");
        // ... set other fields
        courtCaseRepository.save(courtCase);

        Optional<CourtCase> result = courtCaseRepository.findByCnrNumber("TNMD030056782023");
        assertThat(result).isPresent();
    }
}
```

**Note:** `static` on `@Container` means the container starts once per test class
(not once per test method). This is the right default — container startup is slow.

**Why Testcontainers over H2:** In VaadVivaad, Flyway migrations likely use PostgreSQL
syntax. H2 cannot run those migrations. Testcontainers runs the exact same database
as production, catching bugs H2 would miss (e.g., UUID primary key behavior differences,
`jsonb` column types, case-sensitive table names).

---

## 13. Testing Security — @WithMockUser and JWT

Spring Security intercepts requests before your controller runs. In tests you need
to simulate an authenticated user.

### @WithMockUser — the simple approach

```java
@WebMvcTest(CaseLookupController.class)
class SecurityTest {

    @Autowired MockMvc mockMvc;
    @MockBean CaseLookupService caseLookupService;

    @Test
    @WithMockUser  // Adds a mock user to SecurityContext — username="user", roles=["USER"]
    void withAuthenticatedUser_returns200() throws Exception {
        when(caseLookupService.lookupByCnr(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/cases/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cnrNumber\":\"TNMD030056782023\"}")
                .with(csrf()))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")  // Simulates ROLE_ADMIN
    void withAdminUser_canAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isOk());
    }

    @Test
    // No @WithMockUser — anonymous request
    void withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/cases/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cnrNumber\":\"TNMD030056782023\"}"))
            .andExpect(status().isUnauthorized());
    }
}
```

### For JWT-based auth (VaadVivaad uses JWT)

`@WithMockUser` bypasses the JWT filter entirely by directly setting the SecurityContext.
This is fine for most controller tests. But if you want to test the JWT filter itself:

```java
@WebMvcTest(CaseLookupController.class)
class JwtSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockBean CaseLookupService caseLookupService;
    @MockBean JwtService jwtService;  // Mock the JWT service

    @Test
    void withValidJwt_passesFilter() throws Exception {
        // Stub JwtService to accept "valid.token.here"
        when(jwtService.extractUsername("valid.token.here")).thenReturn("ravi@test.com");
        when(jwtService.isTokenValid(eq("valid.token.here"), any())).thenReturn(true);

        mockMvc.perform(post("/api/cases/lookup")
                .header("Authorization", "Bearer valid.token.here")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cnrNumber\":\"TNMD030056782023\"}")
                .with(csrf()))
            .andExpect(status().isOk());
    }
}
```

**Interview insight:** `@WithMockUser` is great for testing authorization rules
(does this endpoint require authentication?). For testing the JWT filter itself,
you need to mock `JwtService` and send real Bearer tokens.

---

## 14. Testing RabbitMQ

VaadVivaad uses RabbitMQ for notifications. Two approaches:

### Option 1: Mock RabbitTemplate (unit/integration test)

```java
@SpringBootTest
class NotificationPublisherTest {

    @Autowired NotificationPublisher notificationPublisher;

    @MockBean
    RabbitTemplate rabbitTemplate;  // Replace real RabbitMQ connection with mock

    @Test
    void whenCaseUpdated_publishesNotificationMessage() {
        // Arrange
        CaseUpdateEvent event = new CaseUpdateEvent("TNMD030056782023", "Status changed");

        // Act
        notificationPublisher.publishCaseUpdate(event);

        // Assert — verify the correct exchange and routing key were used
        verify(rabbitTemplate).convertAndSend(
            eq("vaadvivaad.exchange"),
            eq("case.updated"),
            any(CaseUpdateEvent.class)
        );
    }
}
```

### Option 2: Testcontainers with real RabbitMQ

```java
@SpringBootTest
@Testcontainers
class NotificationPublisherIntegrationTest {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    }

    @Autowired NotificationPublisher notificationPublisher;
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void publishedMessage_canBeConsumed() {
        notificationPublisher.publishCaseUpdate(new CaseUpdateEvent("TNMD...", "Updated"));

        // Use Awaitility to wait for async message consumption
        await().atMost(5, SECONDS).untilAsserted(() -> {
            // check your listener received the message
        });
    }
}
```

---

## 15. Testing @Scheduled Methods

**Do NOT test that `@Scheduled` fires at the right cron time.** Trust Spring. What
you test is the method itself.

```java
// In your scraper service:
@Scheduled(cron = "0 0 2 * * *")   // 2 AM daily
public void scrapeAllCases() {
    // business logic
}

// In your test — call it directly:
@ExtendWith(MockitoExtension.class)
class CaseScraperSchedulerTest {

    @Mock CourtCaseRepository repository;
    @Mock ECourtScraper scraper;
    @InjectMocks CaseScraperScheduler scheduler;

    @Test
    void scrapeAllCases_updatesAllActiveCases() {
        List<CourtCase> cases = List.of(buildCase("CNR1"), buildCase("CNR2"));
        when(repository.findAllActive()).thenReturn(cases);

        // Call the @Scheduled method directly
        scheduler.scrapeAllCases();

        verify(scraper, times(2)).scrape(any());
    }
}
```

For testing that it actually runs on schedule (rare need):
```java
// Add to pom.xml:
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>

// In test — configure a 1-second schedule and wait for it:
@SpringBootTest
class ScheduledJobIntegrationTest {

    @SpyBean CaseScraperScheduler scheduler;

    @Test
    void scheduledJob_runsAtExpectedInterval() throws InterruptedException {
        await()
            .atMost(5, SECONDS)
            .untilAsserted(() ->
                verify(scheduler, atLeastOnce()).scrapeAllCases()
            );
    }
}
```

---

## 16. Test Data Builders — Object Mother Pattern

Repeating `new User(); user.setEmail(...); user.setRole(...)` in every test is noise.
Centralize it.

```java
// src/test/java/com/vaadvivaad/testutil/UserTestBuilder.java
public class UserTestBuilder {

    private UUID id = UUID.randomUUID();
    private String email = "ravi@test.com";
    private String fullName = "Ravi Kumar";
    private String passwordHash = "$2a$encoded";
    private Role role = Role.USER;
    private String phoneNumber = "9876543210";

    public static UserTestBuilder aUser() {
        return new UserTestBuilder();
    }

    public UserTestBuilder withEmail(String email) {
        this.email = email;
        return this;
    }

    public UserTestBuilder withRole(Role role) {
        this.role = role;
        return this;
    }

    public UserTestBuilder asAdmin() {
        this.role = Role.ADMIN;
        return this;
    }

    public User build() {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setPhoneNumber(phoneNumber);
        return user;
    }
}

// src/test/java/com/vaadvivaad/testutil/CourtCaseTestBuilder.java
public class CourtCaseTestBuilder {

    private UUID id = UUID.randomUUID();
    private String cnrNumber = "TNMD030056782023";
    private String caseType = "Civil Suit";
    private LocalDate filingDate = LocalDate.of(2023, 3, 15);
    private CaseStatus status = CaseStatus.PENDING;
    private String petitioner = "Ravi Kumar";
    private String respondent = "State of Tamil Nadu";
    private String courtName = "Principal District Court, Chennai";

    public static CourtCaseTestBuilder aCase() {
        return new CourtCaseTestBuilder();
    }

    public CourtCaseTestBuilder withCnrNumber(String cnr) {
        this.cnrNumber = cnr;
        return this;
    }

    public CourtCaseTestBuilder withStatus(CaseStatus status) {
        this.status = status;
        return this;
    }

    public CourtCase build() {
        CourtCase c = new CourtCase();
        c.setId(id);
        c.setCnrNumber(cnrNumber);
        c.setCaseType(caseType);
        c.setFilingDate(filingDate);
        c.setStatus(status);
        c.setPetitioner(petitioner);
        c.setRespondent(respondent);
        c.setCourtName(courtName);
        return c;
    }
}
```

### Using the builders in tests:

```java
// Before: 10 lines of setup per test
@BeforeEach
void setUp() {
    User admin = UserTestBuilder.aUser().asAdmin().withEmail("admin@test.com").build();
    CourtCase pendingCase = CourtCaseTestBuilder.aCase()
        .withStatus(CaseStatus.PENDING)
        .withCnrNumber("TNMD030056782023")
        .build();
}
```

This is the **Object Mother** pattern. Some teams also use the **Builder** pattern
(Lombok's `@Builder` on entities) or **Fixture** files for JSON test data.

---

## 17. Code Coverage with JaCoCo

JaCoCo (Java Code Coverage) is the standard tool. It integrates with Maven/Gradle
and generates HTML/XML reports.

### Adding JaCoCo to VaadVivaad

```xml
<!-- Add to pom.xml build/plugins section -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
        <execution>
            <id>check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>  <!-- Fail build if < 70% -->
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

```bash
# Run tests and generate coverage report
mvn test jacoco:report

# Report location:
target/site/jacoco/index.html
```

### What coverage % is "enough"?

**70-80% meaningful coverage is better than 100% trivial coverage.**

What NOT to test (it's waste):
- Getters and setters (JPA entities in VaadVivaad)
- Framework infrastructure code (`@Configuration` classes)
- DTOs and records — just data, no logic
- Trivial constructors
- `main()` method in `VaadVivaadApplication`

What you MUST test:
- All service methods (happy path + error cases)
- All custom repository queries (`findByCnrNumber`, `existsByEmail`)
- All controller endpoints (status codes, validation rejection, auth requirement)
- All exception paths (ResourceNotFoundException, CnrValidationException)
- Complex logic: the `register()` method's role fallback logic in AuthService

### Exclude entities and DTOs from coverage

```xml
<configuration>
    <excludes>
        <exclude>com/vaadvivaad/*/entity/**</exclude>
        <exclude>com/vaadvivaad/*/dto/**</exclude>
        <exclude>com/vaadvivaad/VaadVivaadApplication.class</exclude>
    </excludes>
</configuration>
```

---

## 18. Analyzing VaadVivaad's Existing Tests

### VaadVivaadApplicationTests.java

```java
@SpringBootTest
@ActiveProfiles("dev")
class VaadVivaadApplicationTests {
    @Test
    void contextLoads() { }
}
```

**What it tests:** Spring's ApplicationContext starts without errors. No bean
misconfiguration, no missing `@Bean` methods, no circular dependencies.

**What's good:** Uses `@ActiveProfiles("dev")` — this loads dev-specific config
(likely `application-dev.yml`) which points to a real dev database. The test will
fail if the dev database isn't available, which is acceptable for a smoke test.

**What's missing:** This should also be runnable in CI. Consider adding a `test`
profile that points to H2 or Testcontainers.

### ECourtHtmlParserTest.java

This is the best test in the project. Every pattern is right:
- No Spring context (parser is pure Java — `new ECourtHtmlParser()`)
- Uses fixture HTML file (not hardcoded HTML string — maintainable)
- Tests happy path, edge case (null next hearing date), and two exception paths
- Uses AssertJ fluent assertions throughout
- Method names follow `method_condition_outcome` convention
- Comments explain WHY there's no `@SpringBootTest` (interview gold)

**What's missing:**
- No `@ParameterizedTest` for different invalid HTML shapes
- Could add `@DisplayName` for better test output readability

**What's there:**
```java
// Edge case test — null date handled gracefully:
void parse_withNullNextDate_parsesGracefully() {
    ParsedCaseData result = parser.parse(sampleHtml);
    ParsedCaseData.ParsedHearing lastHearing = result.hearings().get(2);
    assertThat(lastHearing.nextHearingDate()).isNull();
}

// Exception test:
void parse_withEmptyHtml_throwsScraperException() {
    assertThatThrownBy(() -> parser.parse(""))
        .isInstanceOf(ScraperException.class)
        .hasMessageContaining("Empty HTML");
}
```

### What's Missing from the Test Suite

The project has exactly 2 test files. For a senior-level Spring Boot application,
you'd expect:

| Missing Test | Type | Priority |
|---|---|---|
| `AuthServiceTest` | Unit (`@ExtendWith(MockitoExtension.class)`) | Critical |
| `CaseLookupServiceTest` | Unit | Critical |
| `CaseLookupControllerTest` | Web slice (`@WebMvcTest`) | High |
| `AuthControllerTest` | Web slice | High |
| `CourtCaseRepositoryTest` | Data slice (`@DataJpaTest`) | High |
| `UserRepositoryTest` | Data slice | Medium |
| `JwtServiceTest` | Unit | Medium |
| `NotificationPublisherTest` | Unit/Integration | Medium |

---

## 19. Node → Java Testing Comparison

| Concept | Node/Jest | Java/Spring Boot |
|---|---|---|
| Test runner | `jest` (CLI) | JUnit 5 (via Maven Surefire plugin) |
| Assertion library | `expect(x).toBe(y)` | AssertJ `assertThat(x).isEqualTo(y)` |
| Mocking | `jest.fn()`, `jest.mock()` | Mockito `@Mock`, `when().thenReturn()` |
| Mock injection | Pass mocks to constructor | `@InjectMocks` (auto-injects mocks) |
| Spy | `jest.spyOn(obj, 'method')` | `@Spy` + `doReturn(x).when(spy).method()` |
| HTTP testing | `supertest(app).get('/path')` | `mockMvc.perform(get("/path"))` |
| Mock HTTP service | `jest.mock('./service')` | `@MockBean ServiceClass` in `@WebMvcTest` |
| DB testing (H2 equiv.) | SQLite in-memory for tests | `@DataJpaTest` with H2 |
| DB testing (real Docker) | `testcontainers` npm package | `@Testcontainers` + `@Container` |
| Auth simulation | Mock middleware in express | `@WithMockUser` |
| Setup/teardown | `beforeEach()`, `afterAll()` | `@BeforeEach`, `@AfterAll` |
| Grouping | `describe('group', () => {})` | `@Nested class Group {}` |
| Data-driven tests | `test.each([[a,b], [c,d]])(fn)` | `@ParameterizedTest @CsvSource(...)` |
| Coverage | Istanbul/nyc (`jest --coverage`) | JaCoCo (`mvn test jacoco:report`) |
| Watch mode | `jest --watch` | `mvn test -Dtest=ClassName` |

**Key mindset difference:** In Node, you control dependency injection explicitly
(you pass mocks into the constructor). In Spring, `@InjectMocks` infers which field
to inject based on type — which is why `@Mock` type must exactly match the field type
in the class under test.

---

## 20. Interview Q&A

**Q1: Explain the difference between @Mock and @MockBean.**

A: `@Mock` is a pure Mockito annotation. It creates a mock but has nothing to do with
Spring — it only works when Mockito is enabled via `@ExtendWith(MockitoExtension.class)`.
`@MockBean` is Spring Test's annotation that creates a Mockito mock AND registers it
as a bean in the Spring ApplicationContext, replacing any existing bean of that type.
You use `@Mock` in pure unit tests (no Spring), and `@MockBean` in slice tests like
`@WebMvcTest` where the controller must receive the mocked service through Spring's
DI mechanism.

---

**Q2: Why would you use @WebMvcTest instead of @SpringBootTest for controller tests?**

A: `@SpringBootTest` loads the entire context including database connections, RabbitMQ,
Redis, all services — it can take 20-30 seconds and requires infrastructure to be
available. `@WebMvcTest` loads only the web layer (controller, security, argument
resolvers) and lets you mock out the service layer with `@MockBean`. Controller tests
should verify HTTP behavior: status codes, request validation, security rules, and
JSON shape. None of that requires a real database. So `@WebMvcTest` gives faster,
more focused, and more reliable tests.

---

**Q3: When does @DataJpaTest fail even though the test logic is correct?**

A: When your application uses PostgreSQL-specific SQL that H2 cannot parse. `@DataJpaTest`
replaces your datasource with H2 by default. If Flyway migrations use PostgreSQL
syntax (like `gen_random_uuid()`, `::jsonb` casting, or `GENERATED ALWAYS AS IDENTITY`),
H2 will fail to run them. The fix is to add `@AutoConfigureTestDatabase(replace = NONE)`
and use Testcontainers to provide a real PostgreSQL instance.

---

**Q4: How do you test a method that's annotated with @Transactional?**

A: In a unit test with `@ExtendWith(MockitoExtension.class)`, `@Transactional` is
effectively ignored because Spring's proxy isn't wrapping the object. You test the
business logic directly. In an integration test (`@SpringBootTest` or `@DataJpaTest`),
the transaction IS active. In `@DataJpaTest`, each test runs in its own transaction
that's rolled back afterward, keeping tests isolated. If you need to test that a
transaction actually rolls back on exception, you need an integration test with a
real (or container) database.

---

**Q5: What's wrong with testing 100% of code including getters and setters?**

A: Coverage is a tool for finding untested business logic, not a goal in itself.
Testing getters and setters adds maintenance overhead (tests break when you refactor
field names) while proving nothing meaningful. The JPA entities in VaadVivaad
(`CourtCase`, `User`) are frameworks — they contain no business decisions. The meaningful
tests are: does `CaseLookupService.lookupByCnr()` throw the right exception? Does
`AuthService.register()` default the role to USER when an invalid role is provided?
That's where bugs live. 70-80% coverage of the service layer with meaningful tests
beats 100% line coverage that includes every getter.

---

**Q6: How would you test the @Scheduled scraper in VaadVivaad?**

A: Never test that the cron expression fires at the right time — that's Spring's job
and Spring has its own tests. Test the METHOD that `@Scheduled` calls. In a unit test
with `@ExtendWith(MockitoExtension.class)`, call `scheduler.scrapeAllCases()` directly,
mock the repositories and scraper, and verify the logic executes correctly. If you
genuinely need to verify scheduling behavior (e.g., the method runs at most once per
hour), use `@SpyBean` on the scheduler class in a `@SpringBootTest` with Awaitility
to wait for the invocation.

---

**Q7: How do you test that an unauthenticated request to a secured endpoint is rejected?**

A: In a `@WebMvcTest` test, simply don't add `@WithMockUser`. Make a `MockMvc` request
without any `Authorization` header. Spring Security's filter chain runs normally in
`@WebMvcTest` (security auto-configuration is included), so it will reject the request
with 401. The key insight is that `@WebMvcTest` does load the security configuration
unlike `@SpringBootTest(webEnvironment=NONE)`.

---

**Q8: What is ArgumentCaptor and when do you use it?**

A: ArgumentCaptor captures the exact argument that was passed to a mocked method so
you can assert on it. You need it when `verify(mock).method(someArg)` isn't sufficient
because you want to inspect the internals of `someArg`. In VaadVivaad's `createCase()`
method, after calling `courtCaseRepository.save(courtCase)`, you'd use an `ArgumentCaptor<CourtCase>`
to capture the entity that was saved and assert `savedEntity.getStatus() == PENDING`
and `savedEntity.getCnrNumber() == "TNMD..."`. Without a captor, you'd only know
`save()` was called — not what was passed to it.

---

**Q9: How does @WithMockUser work internally, and why doesn't it test the JWT filter?**

A: `@WithMockUser` uses a `SecurityContextFactory` that directly populates the
`SecurityContextHolder` before the test runs. This bypasses the entire filter chain
including your `JwtAuthFilter`. So if your filter has a bug (wrong header name, wrong
token parsing), `@WithMockUser` tests would still pass. To test the JWT filter,
you need to either: (a) send a real JWT token and mock `JwtService` to accept it,
or (b) write a dedicated filter test. `@WithMockUser` is appropriate when you're
testing controller authorization logic ("does ROLE_USER get 403 on an admin endpoint?"),
not filter behavior.

---

**Q10: If a colleague says "we have 85% code coverage, we're good" — what do you ask?**

A: "What's in that 85%?" Coverage tools measure WHICH lines were executed, not whether
the assertions were meaningful. Someone could execute every line with `mockMvc.perform(get("/api/cases")).andExpect(status().isOk())` and never assert anything about the response body — that line is "covered" but proves nothing. I'd ask:
1. Do we have tests for exception paths (not-found, validation failure, duplicate key)?
2. Do we test that unauthenticated requests are rejected?
3. Are service tests using ArgumentCaptor to verify the entity was built correctly before saving?
4. Are there tests for edge cases in the business logic (null role defaulting, empty hearing list)?
Coverage is a starting point for finding untested code, not proof of test quality.

---

## Senior Differentiators

These are the answers that separate "knows how to write tests" from
"understands testing architecture":

**1. You can explain WHY a test uses the slice it does.**
Not just "I used `@WebMvcTest` because that's for controllers" but "I used
`@WebMvcTest` because the question I'm asking is 'does this endpoint validate
the request and return the right HTTP status?', and that doesn't require a database
or real service — the service is a collaborator I control."

**2. You know when NOT to mock.**
If `CaseLookupService.mapToResponse()` were a private mapper method, you wouldn't
create a separate test for it — you'd test it indirectly through `lookupByCnr()`.
Seniors don't chase 100% method coverage; they identify the public contracts worth
testing.

**3. You use ArgumentCaptor strategically.**
The most common test mistake: `verify(repo).save(any())`. That proves save was called
but not WHAT was saved. Capturing the argument and asserting on `entity.getStatus()`
and `entity.getCnrNumber()` proves the entire construction path worked correctly.

**4. You understand the context load cost.**
Each `@SpringBootTest` in VaadVivaad takes 10-30 seconds to start. If you have
50 `@SpringBootTest` tests, your CI takes 25 minutes. Seniors organize tests into
proper layers so the context is reused (Spring caches the context if the configuration
hasn't changed between test classes). Using `@WebMvcTest` and `@DataJpaTest` for
separate concerns keeps the total test suite fast.

**5. You know the H2 trap.**
When you say "I'd add Testcontainers to VaadVivaad because the Flyway migrations
use PostgreSQL-specific syntax that H2 can't run", you're showing production
awareness — not just textbook knowledge.

**6. You treat tests as documentation.**
Method name `lookupByCnr_whenFound_returnsCaseResponse` is documentation. When this
test fails six months later, a developer who didn't write the code knows immediately
which method broke, under what condition, and what the expected behavior was. That's
more valuable than a comment.

**7. You separate testing concerns clearly:**
- Business logic correctness → Mockito unit tests (no Spring, instant)
- HTTP contract → `@WebMvcTest` + MockMvc
- SQL correctness → `@DataJpaTest` (or Testcontainers)
- Wiring/configuration → `@SpringBootTest contextLoads()`
- End-to-end flow → one or two RANDOM_PORT tests as smoke tests

**8. You can explain soft assertions.**
When you need to validate multiple fields of a response and want ALL failures reported
at once (not just the first), use `SoftAssertions.assertSoftly()`. This is a maturity
signal — it shows you've debugged tests that fail at assertion #1 and hide the real
problem at assertion #4.

**9. You know what verifyNoInteractions() is for.**
After `lookupByCnr()` throws `ResourceNotFoundException` (case not found), the
hearing repository should NEVER have been called. `verifyNoInteractions(hearingRepository)`
proves this. It's a defensive assertion that the error path exited early as designed.

**10. You can connect testing to deployment confidence.**
"Our `contextLoads()` test catches misconfiguration before prod. Our `@DataJpaTest`
suite catches SQL regressions. Our `@WebMvcTest` suite catches breaking API changes.
Together they give us enough confidence to deploy without manual testing for every
change." That's the conversation seniors have — testing as a deployment enabler,
not a box-ticking exercise.
