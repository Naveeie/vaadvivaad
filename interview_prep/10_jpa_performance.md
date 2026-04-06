# JPA Performance & Hibernate Deep Dive
## VaadVivaad Interview Prep — Session 10

> **Who this is for:** You know Node.js/Sequelize/Prisma well. You're learning Spring Boot and
> Java. This file makes JPA performance *feel logical*, not like a list of annotations to memorize.
>
> **Core mental model:** JPA is an abstraction over SQL. Every abstraction has a cost. The job of
> a senior engineer is knowing *when the abstraction is hiding something expensive* and how to fix it.

---

## Table of Contents

1. [The N+1 Problem — The Most Asked JPA Interview Question](#1-the-n1-problem)
2. [Detecting N+1 — Tools and Techniques](#2-detecting-n1)
3. [Fix 1: JOIN FETCH in JPQL](#3-fix-1-join-fetch)
4. [Fix 2: @EntityGraph — Declarative JOIN FETCH](#4-fix-2-entitygraph)
5. [Fix 3: @BatchSize — The Middle Ground](#5-fix-3-batchsize)
6. [Fix 4: DTO Projections — Only Fetch What You Need](#6-fix-4-dto-projections)
7. [Pagination Performance — The OFFSET Trap](#7-pagination-performance)
8. [Database Indexes — Why Each One Exists in VaadVivaad](#8-database-indexes)
9. [EXPLAIN ANALYZE — Reading PostgreSQL Query Plans](#9-explain-analyze)
10. [Connection Pool Sizing — HikariCP](#10-connection-pool-sizing)
11. [@Transactional(readOnly=true) — More Than a Hint](#11-transactionalreadonlytrue)
12. [Hibernate First/Second Level Cache](#12-hibernate-first--second-level-cache)
13. [Lazy Loading and the Open Session in View Anti-Pattern](#13-open-session-in-view-anti-pattern)
14. [Bulk Operations — @Modifying @Query](#14-bulk-operations)
15. [Statistics and Monitoring](#15-statistics-and-monitoring)
16. [Node.js → Java Translation Guide](#16-nodejs--java-translation-guide)
17. [Senior Interview Q&A — 10 Pairs](#17-senior-interview-qa)
18. [Senior Differentiators](#18-senior-differentiators)

---

## 1. The N+1 Problem

### What Is It?

The N+1 problem is when your code fires **1 query to get a list**, then **N more queries to get
related data for each item** in that list. If you fetch 20 court cases, you end up with 21 queries.
It is the most common JPA performance bug and the most common JPA interview question.

### Why Does It Happen?

JPA's default fetch strategy for `@OneToMany` is **LAZY**. This means the collection is not loaded
from the database until you *access* it. When you iterate over a list of parent entities and touch
the collection on each one, JPA fires a separate SELECT for every parent.

**The deceptive part:** the code *looks* clean. Nothing in the code screams "I'm doing 21 queries."
You have to know this is happening.

### VaadVivaad Example — The Exact Problem in `listCases()`

Look at `CaseLookupService.listCases()`. It has N+1 written into it right now — but the current
implementation avoids it by calling `hearingRepository` explicitly rather than touching
`courtCase.getHearings()`. However, if a developer naively rewrote it using lazy loading:

```java
// PROBLEM: N+1 version — DO NOT write code like this
@Transactional(readOnly = true)
public Page<CaseResponse> listCases(Pageable pageable) {
    Page<CourtCase> casePage = courtCaseRepository.findAll(pageable);

    return casePage.map(courtCase -> {
        // THIS LINE triggers a new SELECT for EACH court case in the page
        // If page size = 20, this is 20 extra queries. Total: 21 queries.
        List<Hearing> hearings = courtCase.getHearings(); // lazy load fires here!

        return mapToResponse(courtCase, hearings);
    });
}
```

**What happens in the database:**

```sql
-- Query 1: Get the page of court cases
SELECT * FROM court_cases LIMIT 20 OFFSET 0;

-- Query 2: Get hearings for case 1
SELECT * FROM hearings WHERE case_id = 'uuid-1';

-- Query 3: Get hearings for case 2
SELECT * FROM hearings WHERE case_id = 'uuid-2';

-- ... 18 more queries ...

-- Query 21: Get hearings for case 20
SELECT * FROM hearings WHERE case_id = 'uuid-20';
```

**Total: 21 queries when 1 (or 2) would suffice.**

### The CourtCase Entity Setup That Enables This

```java
// CourtCase.java — notice fetch type is NOT specified = defaults to LAZY for @OneToMany
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();

@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Subscription> subscriptions = new ArrayList<>();
```

`@OneToMany` defaults to `FetchType.LAZY`. This is correct! Lazy loading is the right default.
The problem is when you *accidentally* trigger those lazy loads in a loop.

Compare with `Hearing.java` — the `@ManyToOne` side defaults to EAGER:

```java
// Hearing.java
@ManyToOne(fetch = FetchType.LAZY)  // explicitly set to LAZY (good practice)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;
```

`@ManyToOne` defaults to `FetchType.EAGER`. The Hearing entity explicitly overrides this to LAZY.
If you left the default, every time you loaded a Hearing, JPA would also load its CourtCase —
which then triggers loading of *all that CourtCase's hearings and subscriptions*. A cascade of
unintended data fetching.

### Node.js Analogy — Sequelize/Prisma

The exact same problem exists in Node.js. The JPA ecosystem just makes it subtler.

```javascript
// Sequelize — N+1 version
const notes = await Note.findAll();          // 1 query

for (const note of notes) {
  const images = await note.getImages();     // 1 query PER note — N+1!
  console.log(images);
}

// Sequelize — FIXED with include (equivalent to JOIN FETCH)
const notes = await Note.findAll({
  include: [{ model: Image }]               // 1 JOIN query — done
});

// Prisma — N+1 version
const notes = await prisma.note.findMany();
for (const note of notes) {
  // Prisma doesn't have lazy loading, so you'd explicitly query:
  const images = await prisma.image.findMany({ where: { noteId: note.id } }); // N+1
}

// Prisma — FIXED
const notes = await prisma.note.findMany({
  include: { images: true }                 // JOIN in one query
});
```

The Java version is more dangerous because the lazy load is *invisible* — it fires when you call
`getHearings()` anywhere in your code, even inside a mapper or a toString().

---

## 2. Detecting N+1

### Method 1: Enable SQL Logging

In `application.yml`, show-sql and format-sql are **not currently enabled** in VaadVivaad's base
config (they're likely in a dev profile). Here's what to add for debugging:

```yaml
# application-dev.yml (dev profile only — never in production)
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE  # shows parameter values
```

With this enabled, you'd see in your logs for an N+1 scenario:

```
Hibernate:
    select
        courtcase0_.id as id1_0_,
        courtcase0_.cnr_number as cnr_numb2_0_,
        ...
    from
        court_cases courtcase0_ limit ? offset ?

Hibernate:
    select
        hearings0_.case_id as case_id2_1_0_,
        hearings0_.id as id1_1_0_,
        ...
    from
        hearings hearings0_
    where
        hearings0_.case_id=?

Hibernate:
    select
        hearings0_.case_id as case_id2_1_0_,
        ...
    from
        hearings hearings0_
    where
        hearings0_.case_id=?

-- ... 18 more identical SELECTs from hearings with different case_id values
```

**Counting repetition in the logs = detecting N+1.** If you see the same query shape repeated
N times with different parameter values, you have N+1.

### Method 2: Count Queries in Tests with datasource-proxy

Add `datasource-proxy` to your test dependencies and assert query counts:

```xml
<!-- pom.xml test dependency -->
<dependency>
    <groupId>net.ttddyy</groupId>
    <artifactId>datasource-proxy</artifactId>
    <version>1.8.1</version>
    <scope>test</scope>
</dependency>
```

```java
@Test
void listCases_shouldNotCauseNPlusOneQueries() {
    // Arrange: create 5 court cases each with 3 hearings
    createTestData(5, 3);
    
    // Act + Assert: verify query count
    assertSelectCount(2); // 1 for cases, 1 for hearings (with JOIN FETCH)
    Page<CaseResponse> result = caseLookupService.listCases(PageRequest.of(0, 5));
    assertThat(result).hasSize(5);
}
```

### Method 3: Hibernate Statistics

Enable in application properties (dev only):

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
```

Then in your logs you'll see:

```
Session Metrics {
    1234567 nanoseconds spent acquiring 1 JDBC connections;
    0 nanoseconds spent releasing 0 JDBC connections;
    21 flushes as part of 21 operations;  <-- RED FLAG: 21 queries for 20 cases
    ...
}
```

### Method 4: EXPLAIN ANALYZE in PostgreSQL

Run the actual query from VaadVivaad in psql or a DB tool:

```sql
EXPLAIN ANALYZE
SELECT h.* FROM hearings h WHERE h.case_id = 'some-uuid';
```

See Section 9 for full EXPLAIN ANALYZE interpretation.

---

## 3. Fix 1: JOIN FETCH

### The Concept

`JOIN FETCH` is JPQL syntax that tells Hibernate: "Do a SQL JOIN and load the related collection
in the same query. Don't defer it." It produces one SQL query with a JOIN instead of N+1 separate
SELECTs.

### Applying to VaadVivaad

Add to `CourtCaseRepository.java`:

```java
// CourtCaseRepository.java — add this method

@Query("SELECT c FROM CourtCase c JOIN FETCH c.hearings WHERE c.id = :id")
Optional<CourtCase> findByIdWithHearings(@Param("id") UUID id);

// For a list — fetches ALL cases with their hearings in one query
@Query("SELECT DISTINCT c FROM CourtCase c LEFT JOIN FETCH c.hearings")
List<CourtCase> findAllWithHearings();

// If you also need subscriptions in the same call — two separate JOIN FETCHes
// WARNING: cannot do two JOIN FETCHes on two *bag* (List) collections in one query
// Use DISTINCT + Set, or do two separate queries
@Query("SELECT DISTINCT c FROM CourtCase c LEFT JOIN FETCH c.hearings LEFT JOIN FETCH c.subscriptions")
List<CourtCase> findAllWithHearingsAndSubscriptions(); // Will throw MultipleBagFetchException!
```

**What SQL this generates:**

```sql
-- Before (N+1): 21 queries for 20 cases
-- Query 1
SELECT * FROM court_cases;
-- Queries 2-21 (one per case)
SELECT * FROM hearings WHERE case_id = ?;

-- After (JOIN FETCH): 1 query
SELECT DISTINCT
    c.id, c.cnr_number, c.case_type, ...,
    h.id, h.case_id, h.hearing_date, h.purpose, h.notes, h.next_hearing_date
FROM court_cases c
LEFT JOIN hearings h ON h.case_id = c.id
```

**Query count: 21 → 1. That is a 20x reduction.**

### The MultipleBagFetchException — The Critical Limitation

Hibernate cannot `JOIN FETCH` two `List` collections simultaneously. A "bag" in Hibernate parlance
is an unordered, duplicates-allowed collection — which is what `List` is from Hibernate's
perspective when not ordered by the DB.

```java
// CourtCase.java has TWO List collections:
private List<Hearing> hearings = new ArrayList<>();
private List<Subscription> subscriptions = new ArrayList<>();

// This query WILL THROW at runtime:
@Query("SELECT c FROM CourtCase c JOIN FETCH c.hearings JOIN FETCH c.subscriptions")
// HibernateException: cannot simultaneously fetch multiple bags
```

**WHY?** When you JOIN two collections, the result set has (cases × hearings × subscriptions) rows.
Hibernate cannot correctly deduplicate this into the entity graph without reading duplicate data.

**Fix options:**
1. Change one collection to `Set<>` — Hibernate can de-dup a set via identity
2. Use `@BatchSize` on the second collection (Section 5)
3. Fetch in two separate queries
4. Use DTO projections (Section 6)

### JOIN FETCH + Pagination — Another Limitation

```java
// THIS WILL WORK but Hibernate will log a WARNING:
@Query("SELECT c FROM CourtCase c LEFT JOIN FETCH c.hearings")
Page<CourtCase> findAllWithHearings(Pageable pageable);
// WARNING: HHH90003004: firstResult/maxResults specified with collection fetch;
// applying in memory!
```

Hibernate fetches ALL rows, then paginates in Java memory. For 100,000 cases, this loads all of
them into JVM heap to give you page 1. This is dangerous at scale.

**Why can't it paginate with JOIN FETCH?** Because the JOIN creates multiple rows per case (one per
hearing). If case 1 has 5 hearings, it has 5 rows in the result set. LIMIT 20 would give you 20
*rows*, not 20 *cases* — the semantics break.

**Fix for paginated list + N+1:**
- Use `@BatchSize` (Section 5)
- Or: paginate without JOIN FETCH, then use a second query to batch-load hearings by case IDs
- Or: use projections that don't need hearings in the list view

---

## 4. Fix 2: @EntityGraph

### The Concept

`@EntityGraph` is a declarative way to specify JOIN FETCH without writing JPQL. You annotate the
repository method, not the query. This is cleaner when you use derived query method names
(like Spring Data's `findByCnrNumber`).

### Applying to VaadVivaad

```java
// CourtCaseRepository.java — enhanced version

@Repository
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> {

    // Existing method — no hearings loaded
    Optional<CourtCase> findByCnrNumber(String cnrNumber);

    // @EntityGraph version — loads hearings in the same query
    @EntityGraph(attributePaths = {"hearings"})
    Optional<CourtCase> findWithHearingsByCnrNumber(String cnrNumber);

    // Works on findById too
    @EntityGraph(attributePaths = {"hearings"})
    Optional<CourtCase> findWithHearingsById(UUID id);

    // Load hearings AND subscriptions (same MultipleBagFetchException risk applies)
    @EntityGraph(attributePaths = {"hearings", "subscriptions"})
    Optional<CourtCase> findWithAllRelationsById(UUID id);

    boolean existsByCnrNumber(String cnrNumber);
}
```

**What SQL it generates** — identical to JOIN FETCH:

```sql
SELECT DISTINCT
    c.id, c.cnr_number, ...,
    h.id, h.hearing_date, ...
FROM court_cases c
LEFT OUTER JOIN hearings h ON h.case_id = c.id
WHERE c.cnr_number = ?
```

### @EntityGraph vs JOIN FETCH — When to Use Which

| Scenario | Use |
|----------|-----|
| Derived query method (`findByCnrNumber`) | `@EntityGraph` — can't add JPQL to derived queries |
| Custom `@Query` JPQL | `JOIN FETCH` — keep it in the query string for clarity |
| Need to conditionally fetch | `JOIN FETCH` — more flexible |
| Named graph reused across methods | `@NamedEntityGraph` on entity + `@EntityGraph(value=...)` |

### Named Entity Graphs (Advanced)

```java
// On the entity class:
@Entity
@Table(name = "court_cases")
@NamedEntityGraph(
    name = "CourtCase.withHearings",
    attributeNodes = @NamedAttributeNode("hearings")
)
public class CourtCase extends Auditable {
    // ...
}

// In the repository:
@EntityGraph(value = "CourtCase.withHearings")
Optional<CourtCase> findByCnrNumber(String cnrNumber);
```

This is useful when the same graph (e.g., "case with hearings") is needed in multiple repositories
or service methods — define once on the entity, reference by name.

---

## 5. Fix 3: @BatchSize

### The Concept

`@BatchSize` is a Hibernate-specific annotation that tells it: "When you lazy-load this collection,
don't do it one at a time. Do it in batches of N." Instead of N queries, you get ceil(N/batchSize)
queries.

It is the right fix when:
- You cannot use JOIN FETCH (e.g., because you also need pagination)
- The N+1 is not catastrophic but still worth reducing

### Applying to VaadVivaad

```java
// CourtCase.java — add @BatchSize on the collection

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "court_cases")
public class CourtCase extends Auditable {

    @OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 20)  // Load hearings in batches of 20 cases at a time
    private List<Hearing> hearings = new ArrayList<>();

    @OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 20)
    private List<Subscription> subscriptions = new ArrayList<>();
}
```

**What SQL this generates:**

```sql
-- Without @BatchSize: 20 court cases = 20 separate hearing queries
SELECT * FROM hearings WHERE case_id = 'uuid-1';
SELECT * FROM hearings WHERE case_id = 'uuid-2';
-- ... 18 more

-- With @BatchSize(size=20): 20 court cases = 1 hearing query using IN clause
SELECT * FROM hearings WHERE case_id IN (
    'uuid-1', 'uuid-2', 'uuid-3', ..., 'uuid-20'
);
```

**Query count: 21 → 2. (1 for cases, 1 for hearings batch)**

### @BatchSize vs JOIN FETCH — The Trade-off

| | JOIN FETCH | @BatchSize |
|--|-----------|------------|
| Queries for 20 cases | 1 | 2 |
| Works with `Pageable` | No (in-memory fallback) | Yes |
| Works with multiple collections | Only with `Set` or two queries | Yes |
| Configuration | Query-level | Entity/collection-level |
| SQL readability | One big JOIN | Multiple IN queries |

Use `@BatchSize` as the default safety net. Use JOIN FETCH when you know you need it and don't
need pagination.

### Global BatchSize Setting

Instead of annotating each collection, set a global default:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 20
```

This is the fastest win. Put this in production. It reduces N+1 problems across your entire
application without changing any entity code.

---

## 6. Fix 4: DTO Projections

### The Concept

Instead of loading the full entity (all columns, all relations), load only the fields your caller
actually needs. This is the biggest lever for list endpoints where you show a summary card, not
the full detail.

For VaadVivaad's case list, the UI probably only needs: `id`, `cnrNumber`, `status`, `petitioner`,
`nextHearingDate`. Not the full CourtCase + all hearings + all subscriptions.

### Interface-Based Projection (Spring Data generates SELECT)

```java
// CaseSummaryProjection.java — interface, not a class
public interface CaseSummaryProjection {
    UUID getId();
    String getCnrNumber();
    String getStatus();
    String getPetitioner();
    // Spring Data derives column names from method names (getId -> id, getCnrNumber -> cnr_number)
}

// CourtCaseRepository.java
@Repository
public interface CourtCaseRepository extends JpaRepository<CourtCase, UUID> {

    // Spring Data generates: SELECT id, cnr_number, status, petitioner FROM court_cases
    // Does NOT load: case_type, filing_number, judge_name, court_name, etc.
    // Does NOT load: hearings collection at all
    Page<CaseSummaryProjection> findAllProjectedBy(Pageable pageable);

    // Can combine with conditions
    List<CaseSummaryProjection> findByStatus(String status);
}
```

**Generated SQL:**

```sql
SELECT c.id, c.cnr_number, c.status, c.petitioner
FROM court_cases c
LIMIT 20 OFFSET 0;
-- Zero joins, zero lazy loads, minimal data transfer
```

### Class-Based Projection (Constructor Expression in JPQL)

```java
// CaseSummaryDTO.java — a plain Java record (Java 16+)
public record CaseSummaryDTO(
    UUID id,
    String cnrNumber,
    String status,
    String petitioner
) {}

// CourtCaseRepository.java
@Query("SELECT new com.vaadvivaad.lookup.dto.CaseSummaryDTO(c.id, c.cnrNumber, c.status, c.petitioner) " +
       "FROM CourtCase c WHERE c.status = :status")
List<CaseSummaryDTO> findSummariesByStatus(@Param("status") String status);
```

This is the "constructor expression" in JPQL. Hibernate calls your DTO's constructor directly —
no entity object is created, no Hibernate session tracks anything, no dirty check at flush.

### Projection Including Nested Data (Nested Projections)

```java
// Interface-based, including next hearing info
public interface CaseSummaryWithNextHearingProjection {
    UUID getId();
    String getCnrNumber();
    String getStatus();
    String getPetitioner();
    // Nested projection for the most recent hearing
    NextHearingInfo getHearings(); // Spring Data handles the join
    
    interface NextHearingInfo {
        LocalDate getNextHearingDate();
        String getPurpose();
    }
}
```

### Node.js Equivalent — Prisma `select`

```javascript
// Prisma — equivalent to projection
const cases = await prisma.courtCase.findMany({
  select: {
    id: true,
    cnrNumber: true,
    status: true,
    petitioner: true,
    // NOT selecting: caseType, filingNumber, judgeName, etc.
    // NOT including hearings collection
  }
});

// Sequelize — attributes array
const cases = await CourtCase.findAll({
  attributes: ['id', 'cnrNumber', 'status', 'petitioner'],
  // No include[] = no joins
});
```

The concept is identical. JPA just gives you two syntactic flavors (interface vs class projection)
and generates the SELECT for you.

### When NOT to Use Projections

- Single entity lookup by ID (`findById`) — load the full entity, you likely need it all
- Write operations — you need the full entity to modify and save it
- When the fields you "don't need" are being included in a JOIN anyway

---

## 7. Pagination Performance

### The OFFSET Trap

`Pageable` in Spring Data translates to SQL `LIMIT ... OFFSET ...`. This works fine at low page
numbers. At scale, it becomes a serious problem.

**How OFFSET works at the DB level:**

```sql
-- Page 1 — fast: scan first 20 rows
SELECT * FROM court_cases ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- Page 5001 — SLOW: scan 100,000 rows, throw away first 99,980, return 20
SELECT * FROM court_cases ORDER BY created_at DESC LIMIT 20 OFFSET 100000;
```

The database reads every row up to the offset, even though it discards them. At offset 100,000,
you're doing a full scan of 100,000 rows to retrieve 20. Cost grows linearly with page number.

### VaadVivaad Current Pagination

```java
// CaseLookupService.java
@Transactional(readOnly = true)
public Page<CaseResponse> listCases(Pageable pageable) {
    Page<CourtCase> casePage = courtCaseRepository.findAll(pageable);
    // findAll(pageable) → SELECT * FROM court_cases LIMIT ? OFFSET ?
    // This is fine for low page numbers
    // At court_cases with 500,000 rows and page 5000, it degrades

    return casePage.map(courtCase -> {
        List<Hearing> hearings = hearingRepository
            .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());
        return mapToResponse(courtCase, hearings);
    });
}
```

Note: `listCases()` is calling `hearingRepository` for each case in the page — this is N+1
(20 cases = 21 queries). For the current MVP with small data, it's acceptable. At scale it's not.

### Fix: Keyset Pagination (Cursor-Based)

Instead of `OFFSET n`, remember the last ID you saw and use `WHERE id > lastId`.

```java
// CourtCaseRepository.java — add keyset pagination support
@Query("SELECT c FROM CourtCase c WHERE c.id > :lastId ORDER BY c.id ASC")
List<CourtCase> findNextPage(@Param("lastId") UUID lastId, Pageable pageable);

// For first page (no cursor)
@Query("SELECT c FROM CourtCase c ORDER BY c.id ASC")
List<CourtCase> findFirstPage(Pageable pageable);
```

```sql
-- Keyset pagination: always fast, regardless of how deep into the list
-- Page 1 (no cursor)
SELECT * FROM court_cases ORDER BY id ASC LIMIT 20;

-- Page N+1 (cursor = last id from previous page)
SELECT * FROM court_cases WHERE id > 'last-uuid' ORDER BY id ASC LIMIT 20;
-- PostgreSQL uses the primary key index — O(log n), not O(n)
```

**Trade-offs of keyset pagination:**
- Cannot jump to arbitrary page ("go to page 500") — only forward navigation
- Cursor must be from a stable, ordered column (usually `id` or `created_at`)
- Works perfectly for "load more" / infinite scroll UIs

**For VaadVivaad:** The case list is not millions of rows yet. OFFSET pagination is fine for MVP.
Knowing when to switch to keyset pagination is a senior differentiator.

### Index Support for Pagination

```sql
-- VaadVivaad has this from V2 migration:
CREATE INDEX idx_court_cases_status ON court_cases(status);

-- If you often sort/filter by status + paginate:
SELECT * FROM court_cases WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT 20;
-- Uses idx_court_cases_status for the WHERE, then sorts

-- A composite index would be better for this query:
CREATE INDEX idx_court_cases_status_created ON court_cases(status, created_at DESC);
-- PostgreSQL can use this for both the filter AND the sort in one index scan
```

---

## 8. Database Indexes

### The Logic Behind Each VaadVivaad Index

An index is a sorted copy of a column (or columns) with pointers back to the full row. Lookups
on indexed columns are O(log n) (B-tree traversal) instead of O(n) (full table scan).

Every index you create has a cost: **INSERT, UPDATE, DELETE become slower** because the index must
also be updated. So you index columns you *filter or join on frequently*, not every column.

### V1: Users Table

```sql
-- V1__create_users_table.sql
CREATE INDEX idx_users_email ON users(email);
```

**Why:** Every login request does `WHERE email = ?`. The users table could grow to thousands of
rows. Without this index, every login scans the full table. With it, PostgreSQL does a B-tree
lookup — finding the row in ~log(n) steps.

`email` is already `UNIQUE` which creates an implicit index. The explicit `CREATE INDEX` is
somewhat redundant here — the UNIQUE constraint already creates a unique index. In practice, the
explicit index is documentation-as-code: it says "we know this matters for performance."

### V2: Court Cases Table

```sql
-- V2__create_court_cases_table.sql
CREATE INDEX idx_court_cases_cnr ON court_cases(cnr_number);
CREATE INDEX idx_court_cases_status ON court_cases(status);
```

`idx_court_cases_cnr` — The primary lookup for this app. Users look up cases by CNR number.
`findByCnrNumber()` and `existsByCnrNumber()` both hit this.

Again, `cnr_number` is `UNIQUE` which creates a unique index automatically. The explicit index is
belt-and-suspenders documentation.

`idx_court_cases_status` — The scraper runs over all PENDING cases. A query like
`WHERE status = 'PENDING'` would be a full table scan without this index. With 100,000 court cases,
most PENDING, the index might not help much (low selectivity — see "When NOT to Index" below).
But for filtering to DISPOSED vs PENDING in an admin view, it helps.

### V3: Hearings Table

```sql
-- V3__create_hearings_table.sql
CREATE INDEX idx_hearings_case_id ON hearings(case_id);
CREATE INDEX idx_hearings_date ON hearings(hearing_date);
```

`idx_hearings_case_id` — Critical. Every time you load a court case's hearings, the query is
`WHERE case_id = ?`. Without this index, PostgreSQL scans ALL hearings rows to find the ones
matching a case. With 50,000 hearings across 10,000 cases, that's a 50,000-row scan per case
lookup.

`idx_hearings_date` — For queries that find upcoming hearings by date range. Could be used for
an admin "what hearings are happening this week" query.

### V7: Next Hearing Date Index

```sql
-- V7__update_hearings_add_next_hearing_date.sql
CREATE INDEX idx_hearings_next_date ON hearings(next_hearing_date);
```

**Why this is important:** The notification scheduler queries this column. Look at
`HearingRepository.java`:

```java
@Query("SELECT h FROM Hearing h JOIN FETCH h.courtCase WHERE h.nextHearingDate = :date")
List<Hearing> findByNextHearingDate(@Param("date") LocalDate date);
```

This query runs on a schedule — probably nightly or hourly. It searches all hearings for those
with `next_hearing_date = today`. Without an index, the scheduler does a full table scan of the
hearings table every run. With `idx_hearings_next_date`, PostgreSQL finds matching rows instantly.

The `JOIN FETCH h.courtCase` here is also smart — it loads the `courtCase` along with the hearing
in one query, avoiding a lazy-load when the notification service accesses `hearing.getCourtCase()`.

### V4: Subscriptions Table

```sql
-- V4__create_subscriptions_table.sql
CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_case_id ON subscriptions(case_id);
```

`idx_subscriptions_user_id` — When a user hits "My Subscriptions", the query is
`WHERE user_id = ?`. `SubscriptionRepository.findByUserId()` uses this.

`idx_subscriptions_case_id` — When looking up who to notify for a given case, the query is
`WHERE case_id = ?`. `SubscriptionRepository.findByCourtCaseId()` uses this.

Both directions are query paths, so both are indexed. This is a classic many-to-many junction
table: you always need indexes on both foreign key columns.

### V5: Notification Logs Table

```sql
-- V5__create_notification_logs_table.sql
CREATE INDEX idx_notification_logs_hearing ON notification_logs(hearing_id);
CREATE INDEX idx_notification_logs_status ON notification_logs(status);
```

`idx_notification_logs_hearing` — For "has this hearing already been notified?" lookups.

`idx_notification_logs_status` — For retry logic: `WHERE status = 'FAILED'`. A cron job would
query this to find notifications that need retrying.

### When NOT to Index

1. **Low-cardinality columns:** A column with few distinct values (like `status` in `court_cases`
   — only 3 values: PENDING, DISPOSED, TRANSFERRED). When 80% of rows have `status = 'PENDING'`,
   an index scan returns 80% of the table — at that point PostgreSQL ignores the index and does a
   seq scan anyway. The index wastes space and slows writes for no benefit.

2. **Small tables:** If the table has 100 rows, a full table scan reads 100 rows. An index lookup
   reads the index tree + the row. For tiny tables, seq scan is often faster. PostgreSQL's query
   planner makes this decision automatically.

3. **Columns never used in WHERE/JOIN/ORDER BY:** Indexing `petitioner` or `respondent` (TEXT
   columns) in court_cases would be wasteful — you don't filter by them.

4. **Heavy-write, low-read tables:** `notification_logs` is an append-heavy table. The status
   index is worth it because the retry job needs it. But if every INSERT also updated the index,
   and reads were rare, you'd reconsider.

---

## 9. EXPLAIN ANALYZE

### Why You Need This

`show-sql=true` shows you *what* query Hibernate sent. `EXPLAIN ANALYZE` shows you *how PostgreSQL
executed it* — which indexes it used, how many rows it actually scanned, how long each step took.

This is the difference between knowing your code fired a slow query and knowing *why* it's slow.

### Syntax

```sql
EXPLAIN ANALYZE <your query here>;

-- Example with VaadVivaad:
EXPLAIN ANALYZE
SELECT h.id, h.hearing_date, h.purpose, h.notes, h.next_hearing_date
FROM hearings h
WHERE h.case_id = '550e8400-e29b-41d4-a716-446655440000'
ORDER BY h.hearing_date DESC;
```

### Reading the Output

```
Index Scan using idx_hearings_case_id on hearings  (cost=0.43..8.45 rows=5 width=200)
                                                     (actual time=0.021..0.034 rows=7 loops=1)
  Index Cond: (case_id = '550e8400...'::uuid)
  -> Sort  (cost=8.47..8.48 rows=5 width=200) (actual time=0.038..0.039 rows=7 loops=1)
        Sort Key: hearing_date DESC
Planning Time: 0.312 ms
Execution Time: 0.067 ms
```

**Reading left to right:**

- `Index Scan using idx_hearings_case_id` — PostgreSQL used the index, not a full table scan.
  This is what you want.
- `cost=0.43..8.45` — Planner's *estimated* cost (arbitrary units). Lower is better. First number
  is startup cost (before first row returned), second is total cost.
- `rows=5` — Planner *estimated* 5 rows would match.
- `actual time=0.021..0.034` — Wall clock time in milliseconds. First number: time to first row.
  Second: total time.
- `rows=7 loops=1` — Planner estimated 5, actually got 7. The estimates are close — good.
  When estimates are wildly off (estimated 5, got 500,000), the planner made a bad choice and you
  may need to run `ANALYZE hearings;` to update statistics.

### Seq Scan vs Index Scan vs Index Only Scan

```
Seq Scan on court_cases  (cost=0.00..25.00 rows=1000 width=300)
                          (actual time=0.012..2.341 rows=1000 loops=1)
```

**Seq Scan** — Full table scan. PostgreSQL reads every row. Fine for small tables or when
returning most of the table. Bad sign on large tables with a narrow WHERE clause.

```
Index Scan using idx_court_cases_cnr on court_cases
```

**Index Scan** — Uses the B-tree index to find matching rows, then fetches the full row from the
table (heap). Good for high-selectivity queries (narrow WHERE clause matching few rows).

```
Index Only Scan using idx_court_cases_cnr on court_cases
```

**Index Only Scan** — The query needs only columns that are in the index. PostgreSQL doesn't even
touch the table (heap) — gets everything from the index alone. Fastest possible scan. This happens
when you use projections that match indexed columns.

### A Real VaadVivaad Slow Query Scenario

Suppose the scheduler query is running slowly:

```sql
EXPLAIN ANALYZE
SELECT h.id, h.next_hearing_date, c.cnr_number, c.id as case_id
FROM hearings h
JOIN court_cases c ON c.id = h.case_id
WHERE h.next_hearing_date = CURRENT_DATE;
```

**Before `idx_hearings_next_date` existed (V7 migration):**

```
Hash Join  (cost=450.00..900.00 rows=50 width=100)
           (actual time=10.234..45.123 rows=23 loops=1)
  Hash Cond: (h.case_id = c.id)
  -> Seq Scan on hearings  (cost=0.00..450.00 rows=50000 width=50)
                            (actual time=0.010..20.123 rows=50000 loops=1)
       Filter: (next_hearing_date = CURRENT_DATE)
       Rows Removed by Filter: 49977
  -> Hash  (cost=200.00..200.00 rows=10000 width=50) ...

Execution Time: 45.234 ms
```

**After adding `idx_hearings_next_date`:**

```
Nested Loop  (cost=0.43..25.00 rows=23 width=100)
             (actual time=0.045..0.234 rows=23 loops=1)
  -> Index Scan using idx_hearings_next_date on hearings
       Index Cond: (next_hearing_date = CURRENT_DATE)
       actual rows=23, loops=1
  -> Index Scan using court_cases_pkey on court_cases
       Index Cond: (id = h.case_id)

Execution Time: 0.312 ms
```

**45ms → 0.3ms.** A 150x improvement by adding one index. This is why Flyway migrations are not
just schema — they are performance decisions.

### The `Rows Removed by Filter` Line

When you see `Rows Removed by Filter: 49977` on a Seq Scan, that means PostgreSQL read 50,000 rows
and threw away 49,977 to give you 23. This is the red flag that says "you need an index here."

---

## 10. Connection Pool Sizing

### HikariCP — Spring Boot's Default Pool

Spring Boot auto-configures HikariCP. A connection pool maintains a set of open connections to
the database, reusing them across requests instead of opening/closing a connection per request.

Opening a PostgreSQL connection takes ~50-100ms (TCP handshake, auth, session setup). Reusing
pooled connections makes that cost zero for subsequent requests.

### Key Configuration Parameters

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/vaadvivaad
    hikari:
      maximum-pool-size: 10      # Max connections Hikari will open to PostgreSQL
      minimum-idle: 5            # Keep at least 5 connections open even when idle
      connection-timeout: 30000  # Wait up to 30s for a connection from pool (ms)
      idle-timeout: 600000       # Close idle connections after 10 minutes (ms)
      max-lifetime: 1800000      # Replace a connection after 30 minutes (ms)
      pool-name: VaadVivaadPool
```

### The Pool Sizing Formula

From the HikariCP author (Brett Wooldridge):

```
pool_size = (core_count * 2) + effective_spindle_count
```

- `core_count` = number of CPU cores on the *database server*
- `effective_spindle_count` = number of disks (1 for SSD, disk count for spinning disk array)

For a typical small server (2 cores, SSD):
```
pool_size = (2 * 2) + 1 = 5
```

For a medium server (4 cores, SSD):
```
pool_size = (4 * 2) + 1 = 9  →  round to 10
```

**Counterintuitive insight:** More connections does NOT mean better performance. PostgreSQL has
per-connection overhead. At 200 connections, PostgreSQL spends more time managing connections than
running queries. The formula exists for a reason — don't set `maximumPoolSize=200`.

### How It Relates to @Transactional

Every `@Transactional` method borrows a connection from HikariCP for its duration. If all pool
connections are in use:
- New requests wait up to `connectionTimeout` (30s)
- After that: `SQLTimeoutException`

A long-running `@Transactional` method that calls external APIs (like `ScraperService.upsertCase`
which calls `eCourtWebClient.fetchCaseHtml`) holds a database connection while waiting for HTTP.
This is a pool starvation pattern — fix it by doing HTTP calls *outside* the transaction, or by
structuring code so the DB transaction is only open during the DB operations.

---

## 11. @Transactional(readOnly=true)

### What It Actually Does

```java
// CaseLookupService.java — already has this correctly
@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "#cnrNumber")
public CaseResponse lookupByCnr(String cnrNumber) {
    // ...
}

@Transactional(readOnly = true)
@Cacheable(value = "cases", key = "'id:' + #id")
public CaseResponse lookupById(UUID id) {
    // ...
}

@Transactional(readOnly = true)
public Page<CaseResponse> listCases(Pageable pageable) {
    // ...
}
```

Three concrete things happen with `readOnly = true`:

**1. Hibernate skips dirty checking at flush.**
Normally, at the end of a transaction, Hibernate compares every managed entity's current state to
its state when loaded ("dirty check") and generates UPDATE statements for changed entities. This
comparison runs for every entity in the session's first-level cache. For a read of 20 court cases,
Hibernate checks all 20 for modifications at flush time. `readOnly = true` sets flush mode to
`MANUAL`, which skips this entirely. The savings grow with the number of entities in the session.

**2. Some JDBC drivers and connection proxies route to a read replica.**
PostgreSQL + certain proxies (PgBouncer in certain modes, read-routing proxies like RDS Proxy)
can see the JDBC `Connection.setReadOnly(true)` call and route the connection to a read replica.
VaadVivaad doesn't use read replicas yet — but the annotation makes it forward-compatible.

**3. Spring sets `connection.setReadOnly(true)` on the JDBC connection.**
This hints to the database that no writes will occur. PostgreSQL can optimize accordingly (skip
write conflict checking).

**Mental model:** `readOnly = true` is a promise: "I will not modify any entity in this method."
It is both a performance optimization and a documentation of intent. If you accidentally try to
save inside a `readOnly = true` transaction, you get an exception — good.

### Where VaadVivaad Uses readOnly=true

All three read methods in `CaseLookupService` are correctly annotated with `readOnly = true`.
`ScraperService.scrapeOrRefresh` and `CaseLookupService.createCase` are regular `@Transactional`
(not readOnly) because they write to the database.

---

## 12. Hibernate First / Second Level Cache

### First Level Cache — Always On, Per-Session

The first-level cache is Hibernate's session-scoped identity map. Within a single `@Transactional`
method (= one Hibernate session), Hibernate guarantees that if you load the same entity ID twice,
it returns the *same Java object* from memory — no second DB query.

```java
@Transactional
public void example() {
    // Query 1: hits the database
    CourtCase case1 = courtCaseRepository.findById(someId).get();
    
    // Query 2: returns the SAME object from session cache, NO database query
    CourtCase case2 = courtCaseRepository.findById(someId).get();
    
    System.out.println(case1 == case2); // true — same Java object reference
}
```

This is why `@Modifying(clearAutomatically = true)` exists — bulk UPDATE/DELETE queries bypass the
first-level cache. After a bulk update, entities in the cache are stale. `clearAutomatically = true`
forces Hibernate to clear the first-level cache so subsequent reads get fresh data from DB.

```java
// HearingRepository.java already has this pattern:
@Modifying
@Query("DELETE FROM Hearing h WHERE h.courtCase.id = :caseId")
void deleteAllByCaseId(@Param("caseId") UUID caseId);
// If you then do hearingRepository.findAll(), you'd get stale results
// unless the cache is cleared
```

### Second Level Cache — Shared, Off by Default

The second-level cache is shared across all sessions (all requests). It means two different HTTP
requests can share cached entity state — the entity is only loaded from DB once, then served from
L2 cache for subsequent requests.

**It is off by default and should be turned on deliberately.**

```xml
<!-- pom.xml — add EhCache provider -->
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-jcache</artifactId>
</dependency>
<dependency>
    <groupId>org.ehcache</groupId>
    <artifactId>ehcache</artifactId>
</dependency>
```

```yaml
spring:
  jpa:
    properties:
      hibernate:
        cache:
          use_second_level_cache: true
          use_query_cache: true
          region.factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

```java
// On the entity — opt in per entity
@Entity
@Cacheable
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class CourtCase extends Auditable {
    // ...
}
```

**VaadVivaad already has Spring Cache (`@Cacheable`) with Redis**, which is a higher-level cache
on the service method result (CaseResponse DTO). The Hibernate L2 cache would operate at the
entity level. Having both is redundant — the Spring Cache already prevents most redundant DB reads.

**Risk of L2 cache:** Stale data. If you update a CourtCase through a bulk SQL query (bypassing
Hibernate), the L2 cache still holds the old version. Every cache layer adds a cache invalidation
problem.

### Query Cache

```java
// Opt-in per query
@QueryHint(name = "org.hibernate.cacheable", value = "true")
@Query("SELECT c FROM CourtCase c WHERE c.status = 'PENDING'")
List<CourtCase> findAllPending();
```

The query cache caches the *IDs* returned by a query. On subsequent calls, Hibernate fetches
entities by those IDs from L2 cache (or DB if not cached). This is rarely worth the complexity.
Hard to invalidate correctly — if any CourtCase changes status, the cache must be invalidated.
VaadVivaad already achieves this via `@Cacheable` on `lookupByCnr`. The query cache is more
appropriate for truly static reference data (lookup tables, config data).

---

## 13. Open Session in View Anti-Pattern

### The Problem

By default, Spring Boot sets `spring.jpa.open-in-view=true`. This keeps the Hibernate session open
through the *entire HTTP request lifecycle*, including after the service method returns, through the
controller, and through view rendering (Thymeleaf templates, etc.).

**Why this is bad:**

```
HTTP Request arrives
    → Controller.method() called
        → Service.listCases() called           ← @Transactional session starts
            → courtCaseRepository.findAll()    ← 20 CourtCase loaded, hearings are LAZY
        → Service.listCases() returns          ← @Transactional commits, session STAYS OPEN
    → JSON serializer runs...
        → serializes courtCase.getHearings()   ← TRIGGERS LAZY LOAD (session still open!)
        → fires 20 more queries to DB          ← N+1 INVISIBLE IN SERVICE LAYER
```

The session is still open in the controller/serializer layer. Lazy loads fire silently.
You cannot detect this in the service layer because the queries happen elsewhere.

### VaadVivaad's Configuration

```yaml
# application.yml — VaadVivaad correctly disables OSIV
spring:
  jpa:
    properties:
      open-in-view: false   # CORRECT — explicitly disabled
```

With `open-in-view: false`, the session closes when the `@Transactional` method returns. Any lazy
load triggered after that throws `LazyInitializationException`.

```
LazyInitializationException: failed to lazily initialize a collection of role:
com.vaadvivaad.lookup.entity.CourtCase.hearings - no Session
```

This exception is actually *good* — it makes N+1 problems visible at development time instead of
silently degrading production performance.

**The pattern OSIV=false forces on you:**
- Service methods must eagerly load everything the caller needs before returning
- Or service methods return DTOs (not entities) — DTOs have no lazy loading
- Controllers must not access JPA entity collections

This is the correct architecture. VaadVivaad does it right — `CaseLookupService.lookupByCnr()`
explicitly loads hearings via `hearingRepository.findByCourtCaseIdOrderByHearingDateDesc()` before
returning the DTO.

### Node.js Equivalent

Node.js ORMs don't have this problem in the same way because they don't have sessions or proxy
objects. Sequelize/Prisma return plain JavaScript objects — there's no "open session" concept.
The equivalent anti-pattern in Node.js is calling `findAll()` in middleware and then calling
`note.getImages()` in the route handler without including it in the original query.

---

## 14. Bulk Operations

### Why @Modifying @Query Exists

```java
// HearingRepository.java — real example from VaadVivaad
@Modifying
@Query("DELETE FROM Hearing h WHERE h.courtCase.id = :caseId")
void deleteAllByCaseId(@Param("caseId") UUID caseId);
```

The comments in `HearingRepository.java` explain the "why" perfectly:

> Spring Data derived queries for delete return the deleted entities (first loads, then deletes each).
> For 10 hearings that means 10 SELECTs then 10 DELETEs.
> `@Modifying + @Query` generates a single `DELETE FROM hearings WHERE case_id = ?`

This is the core principle: **bulk operations bypass the entity lifecycle.** They don't load
entities, don't run lifecycle callbacks (`@PreRemove`, etc.), don't update the L1 cache, and they're
executed as a single SQL statement.

### Bulk UPDATE Example

```java
// In CourtCaseRepository — could be added for scraper maintenance
@Modifying
@Transactional
@Query("UPDATE CourtCase c SET c.status = :newStatus WHERE c.lastScrapedAt < :cutoff")
int markOldCasesAsStale(@Param("newStatus") CaseStatus newStatus,
                         @Param("cutoff") LocalDateTime cutoff);
```

**What SQL this generates:**
```sql
UPDATE court_cases
SET status = ?
WHERE last_scraped_at < ?
```

Compare to the naive approach:
```java
// SLOW: load all old cases into JVM, modify, save one by one
List<CourtCase> oldCases = courtCaseRepository.findByLastScrapedAtBefore(cutoff);
for (CourtCase c : oldCases) {
    c.setStatus(CaseStatus.DISPOSED);
    courtCaseRepository.save(c);    // generates UPDATE per entity
}
```

For 1,000 cases: bulk = 1 UPDATE. Naive = 1 SELECT + 1,000 UPDATEs.

### clearAutomatically — The Critical Detail

```java
@Modifying(clearAutomatically = true)  // <-- important flag
@Query("UPDATE CourtCase c SET c.status = :status WHERE c.id IN :ids")
void updateStatusBatch(@Param("status") CaseStatus status, @Param("ids") List<UUID> ids);
```

Without `clearAutomatically = true`:
- The bulk UPDATE modifies rows in the database
- The Hibernate L1 cache still has the old entity state
- If you then call `courtCaseRepository.findById(id)`, Hibernate returns the stale cached version
- Your application is now working with data inconsistent with the database

With `clearAutomatically = true`:
- After the bulk UPDATE, Hibernate clears the entire L1 cache
- Next read hits the database and gets fresh data
- Cost: any entity you had loaded before the update must be re-fetched

### @Modifying Must Be Inside @Transactional

```java
// ScraperService.java — @Transactional at the method level covers the repo calls inside
@Transactional
public CourtCase scrapeOrRefresh(String cnrNumber) {
    // ...
    hearingRepository.deleteAllByCaseId(saved.getId());  // @Modifying method
    // Works because @Transactional on scrapeOrRefresh provides the transaction
}
```

`@Modifying` methods must run within a transaction. If you call a `@Modifying` method without an
active transaction, you get `TransactionRequiredException`. The `@Transactional` on the calling
service method provides the transaction context — the `@Modifying` method participates in it.

---

## 15. Statistics and Monitoring

### Hibernate Statistics — Development Only

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
        session:
          events:
            log:
              LOG_QUERIES_SLOWER_THAN_MS: 100  # Log queries slower than 100ms
```

This outputs session metrics after each request:

```
Session Metrics {
    123456 nanoseconds spent acquiring 1 JDBC connections;
    1234567 nanoseconds spent preparing 21 JDBC statements;  # <-- 21 = N+1!
    2345678 nanoseconds spent executing 21 JDBC statements;
    0 nanoseconds spent executing 0 JDBC batches;
    345678 nanoseconds spent performing 1 L2C puts;
    234567 nanoseconds spent performing 0 L2C hits;
}
```

Seeing "21 JDBC statements" for a page of 20 cases is your N+1 alarm.

### Spring Boot Actuator Metrics

VaadVivaad's `application.yml` exposes `health` and `info` only:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

For production monitoring, add metrics:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

Then visit `/actuator/metrics/hikaricp.connections.active` to see live pool usage,
`/actuator/metrics/http.server.requests` for request latency by endpoint.

### PostgreSQL Slow Query Log

On the PostgreSQL server (or in `postgresql.conf`):

```sql
-- Log all queries slower than 1 second
ALTER SYSTEM SET log_min_duration_statement = 1000;
SELECT pg_reload_conf();
```

In production, slow queries appear in the PostgreSQL log:

```
LOG: duration: 1234.567 ms  statement: SELECT * FROM court_cases LIMIT 20 OFFSET 100000
```

This tells you *which* query is slow without having to reproduce it in development.

### Combining Signals

In production, you typically use all three together:
1. **Application logs** (Hibernate statistics) → "21 queries per request"
2. **PostgreSQL slow query log** → "SELECT from hearings WHERE case_id = ? ran 5ms × 20 times"
3. **EXPLAIN ANALYZE** → "Seq Scan on hearings (50,000 rows examined)"
4. **APM tool** (Datadog, New Relic, Jaeger) → "listCases endpoint p99 = 800ms"

---

## 16. Node.js → Java Translation Guide

| Node.js Concept | Java/JPA Equivalent | Key Difference |
|----------------|---------------------|----------------|
| `Model.findAll()` | `repository.findAll()` | JPA returns proxy objects with lazy-load capability |
| `Model.findAll({ include: [Model2] })` | `JOIN FETCH` in JPQL or `@EntityGraph` | JPA defaults to lazy; you must opt in to eager |
| Sequelize `attributes: ['id', 'name']` | Interface projection or constructor expression | Spring Data generates the SELECT for you |
| Prisma `select: { id: true }` | `CaseSummaryProjection` interface | Almost identical concept |
| Mongoose `.lean()` | DTO projection (no managed entity) | .lean() removes Mongoose proxy; JPA DTO removes Hibernate tracking |
| `Model.bulkCreate()` | `saveAll()` + JDBC batch | Need to enable `spring.jpa.properties.hibernate.jdbc.batch_size` |
| `Model.update({}, { where: {} })` | `@Modifying @Query("UPDATE ...")` | JPA bulk update bypasses entity lifecycle |
| `Model.destroy({ where: {} })` | `@Modifying @Query("DELETE FROM ...")` | Same — avoids load-then-delete |
| Sequelize transactions | `@Transactional` | Spring manages begin/commit/rollback |
| `sequelize.query("SELECT ...")` | `@Query(nativeQuery = true, ...)` | Escape hatch to raw SQL |
| No concept | First-level cache | JPA caches entities within a session automatically |
| No concept | `open-in-view` | Node.js has no session concept; JPA does |

### Sequelize `include` vs JPQL `JOIN FETCH`

```javascript
// Sequelize
const cases = await CourtCase.findAll({
    include: [{ model: Hearing, order: [['hearingDate', 'DESC']] }]
});
```

```java
// JPQL equivalent
@Query("SELECT c FROM CourtCase c LEFT JOIN FETCH c.hearings h ORDER BY h.hearingDate DESC")
List<CourtCase> findAllWithHearingsOrdered();
```

Both produce one SQL query with a JOIN. The difference: Sequelize requires the `include` every
time you call `findAll`. JPA requires you to know that without JOIN FETCH (or @EntityGraph),
you get N+1 — the lazy loading hides the N queries until you trigger them.

---

## 17. Senior Interview Q&A

### Q1: What is the N+1 problem and how would you detect it in a Spring Boot application?

**Answer:** The N+1 problem occurs when loading a list of entities triggers one additional query
per entity to load a related collection. For 20 court cases, you get 1 SELECT for cases plus 20
SELECTs for hearings — 21 queries instead of 1 or 2.

Detection: Enable `spring.jpa.show-sql=true` and count repeated query shapes in the logs. Or
enable `hibernate.generate_statistics=true` and check the JDBC statement count per request. A
page of 20 results that generates 21+ statements is a clear N+1 signal.

In VaadVivaad, `listCases()` has a latent N+1: it calls `hearingRepository.findByCourtCaseId()`
inside a `Page.map()` loop — one query per case. Currently it avoids touching lazy-loaded
collections, but the pattern is still N+1 (21 queries for 20 cases).

---

### Q2: What's the difference between JOIN FETCH and @EntityGraph?

**Answer:** Both solve N+1 by loading related entities in one JOIN query. The choice is syntactic:

`JOIN FETCH` is inside the JPQL query string — you write it in `@Query`. It's explicit and
visible. Use it for custom queries.

`@EntityGraph` is an annotation on the repository method. It works with derived query names
(`findByCnrNumber`) where you can't add JPQL. It's declarative — Spring Data adds the join for you.

Neither works cleanly with pagination when the fetched collection is a List (bag) — you get the
`HHH90003004` warning and in-memory pagination. Use `@BatchSize` when you need pagination.

---

### Q3: Can you use JOIN FETCH with Pageable?

**Answer:** Technically yes, but you shouldn't. Hibernate issues a warning and paginates *in
memory* — it fetches all matching rows and sorts/limits in the JVM, not in the database. For large
datasets this loads everything into heap and defeats the purpose of pagination.

The correct approach for paginated lists with related data: use `@BatchSize` on the collection (or
`spring.jpa.properties.hibernate.default_batch_fetch_size=20` globally). This paginates correctly
in SQL, then batch-loads the related collection with an IN clause.

---

### Q4: What does @Transactional(readOnly=true) actually do at the database level?

**Answer:** Three things:

1. Sets Hibernate's flush mode to MANUAL — skips the dirty-check comparison at transaction end.
   For a read of 20 entities, this avoids 20 state comparisons.
2. Calls `connection.setReadOnly(true)` on the JDBC connection — hints to the DB and allows
   routing to read replicas through database proxies.
3. Spring/Hibernate can skip acquiring write locks in some scenarios.

It's also documentation: it declares intent that this method won't modify entities. If you
accidentally try to save inside a `readOnly = true` transaction, you get an exception — good.

---

### Q5: What happens if open-in-view is true and why is it a problem?

**Answer:** With `spring.jpa.open-in-view=true` (Spring Boot's historical default), the Hibernate
session stays open through the entire HTTP request, including after the `@Transactional` service
method returns. This allows lazy loads to fire in controllers, JSON serializers, and view templates.

The problem: N+1 queries become invisible. The service layer looks clean. But when Jackson
serializes a `CourtCase` and touches `courtCase.getHearings()`, Hibernate fires a query. For 20
cases on a page, that's 20 hidden queries.

Setting `open-in-view: false` (as VaadVivaad does) closes the session after `@Transactional`
completes. Lazy loads outside the transaction throw `LazyInitializationException` — making the
problem visible at development time. The fix: explicitly load what you need in the service layer
and return DTOs, not entities.

---

### Q6: What is the @Modifying annotation and why is clearAutomatically important?

**Answer:** `@Modifying` tells Spring Data that a `@Query` performs a write (UPDATE or DELETE) —
not a SELECT. Without it, Spring Data assumes queries are reads and the return type would be wrong.

`clearAutomatically = true` tells Hibernate to clear the first-level (session) cache after the
bulk operation executes. Bulk operations go directly to the database, bypassing the L1 cache.
Without clearing, the cache holds stale entity state. Any entity loaded before the bulk UPDATE
is now inconsistent with what's in the database. With `clearAutomatically = true`, the next read
goes to the database and gets fresh data.

---

### Q7: How would you handle the case where you need paginated court cases AND their hearings efficiently?

**Answer:** Two options:

Option A (simplest, usually best): Set `spring.jpa.properties.hibernate.default_batch_fetch_size=20`.
Hibernate batch-loads hearings for all cases in the page with one `WHERE case_id IN (...)` query.
Total: 2 queries — one for the page, one batch for all hearings. No code changes needed.

Option B (explicit): Fetch paginated court cases normally, collect their IDs, then call
`hearingRepository.findByCaseIdIn(caseIds)` as a separate query. Map hearings back to cases in
Java. Total: 2 queries. More explicit but requires more code.

What NOT to do: `JOIN FETCH` with `Pageable` — causes in-memory pagination, dangerous at scale.
What NOT to do: loop `findByCourtCaseId()` per case — that's N+1.

---

### Q8: Explain the difference between first-level and second-level cache in Hibernate.

**Answer:** First-level (L1) cache is per-session — effectively per-transaction. It's always on.
If you load a `CourtCase` by ID twice in the same `@Transactional` method, the second call returns
the same Java object from memory, no DB query. This is Hibernate's identity map — it guarantees
object identity within a session.

Second-level (L2) cache is shared across all sessions — all HTTP requests share it. Off by default,
requires explicit setup (EhCache, Redis, Infinispan). Entities marked `@Cacheable` are stored in
L2 cache. When any session loads them, Hibernate checks L2 first. Reduces DB load significantly
for frequently-read, rarely-changed entities.

Risk: L2 cache can serve stale data. If you update an entity through native SQL or a `@Modifying`
query, the L2 cache is not automatically invalidated — the cache doesn't know about it.

VaadVivaad uses Spring Cache (`@Cacheable`) at the service level, which is functionally similar
to L2 cache but operates on DTOs, not Hibernate entities. No L2 Hibernate cache is configured.

---

### Q9: What is keyset pagination and when would you use it in VaadVivaad?

**Answer:** Keyset (cursor) pagination uses `WHERE id > lastSeenId ORDER BY id` instead of
`OFFSET n`. The database uses the primary key index to find the starting point — O(log n)
regardless of which "page" you're on. OFFSET pagination reads and discards all rows up to the
offset — O(n) that degrades linearly with page depth.

For VaadVivaad's current size (hundreds of court cases), OFFSET is fine. At 100,000+ cases with
users paging deep into the list, keyset becomes necessary. The trade-off: you lose random page
access ("jump to page 500") and must expose a `cursor` token to the client instead of a page
number. This works well for "load more" / infinite scroll UIs.

The `findByNextHearingDate` use case in the scheduler is not a pagination problem — it loads all
hearings for a specific date in one query.

---

### Q10: You've noticed that the VaadVivaad case list endpoint takes 800ms in production. Walk me through how you'd diagnose it.

**Answer:** Systematic approach:

Step 1: Check application logs with `show-sql=true` (dev environment reproduction). Count the
queries. If 21 queries for 20 cases → N+1 confirmed.

Step 2: Check Hibernate statistics (`generate_statistics=true`). JDBC statement count tells you
the actual query count without reading every log line.

Step 3: Check the PostgreSQL slow query log for the top queries by cumulative time:
```sql
SELECT query, calls, total_exec_time, mean_exec_time
FROM pg_stat_statements
ORDER BY total_exec_time DESC LIMIT 10;
```

Step 4: Run `EXPLAIN ANALYZE` on the specific slow query. Look for Seq Scans on large tables,
`Rows Removed by Filter` with high counts, or nested loops on un-indexed joins.

Step 5: Check HikariCP pool metrics via Actuator. If `hikaricp.connections.pending > 0`, the pool
is undersized or there are long-running transactions holding connections.

In VaadVivaad specifically, the most likely cause of 800ms on `listCases` is N+1: 21 queries with
network round-trips add up. Fix: `default_batch_fetch_size=20` or explicit batch hearing load.
Secondary suspect: missing index — check `EXPLAIN ANALYZE` on the hearing query.

---

## 18. Senior Differentiators

These are the things that separate "knows the annotation" from "understands the system."

### 1. You understand WHY lazy loading is the right default

Lazy loading exists because you often don't need relations. Loading `CourtCase` for `existsByCnrNumber` shouldn't also load 50 hearings. Eager loading is convenient but expensive at scale. The senior move is keeping lazy as default and being deliberate about when to fetch eagerly — not disabling lazy loading globally to avoid N+1.

### 2. You know that open-in-view=false is non-negotiable

Spring Boot defaults to `open-in-view=true` for historical compatibility with Thymeleaf-based MVC apps. For REST APIs, it is always wrong. You set it to false and handle loading explicitly in the service layer. VaadVivaad already does this correctly.

### 3. You understand that indexes have a write cost

Adding an index on every column is not "safer." Every write to `court_cases` also writes to `idx_court_cases_cnr`, `idx_court_cases_status`, and the primary key index. The write overhead is usually acceptable. But for a write-heavy table (like `notification_logs` which gets many inserts), you add only the indexes that are actually queried.

### 4. You can explain the MultipleBagFetchException

When asked "can you JOIN FETCH two collections?" you don't say "no." You say: "You cannot JOIN FETCH two `List` (bag) collections simultaneously because the cartesian product creates ambiguous deduplication. The fix is to change one collection to `Set`, use `@BatchSize` on the second collection, or fetch in two separate queries." This shows you understand the mechanism, not just the error.

### 5. You connect @Modifying to cache invalidation

`@Modifying` bulk operations bypass the first-level cache. Anyone who's only memorized `@Modifying` will write a bug: bulk UPDATE runs, then reads from cache get stale data. You know to add `clearAutomatically = true` and why it's needed.

### 6. You know when NOT to use JOIN FETCH

JOIN FETCH with Pageable causes in-memory pagination. You don't just say "use JOIN FETCH to fix N+1" — you qualify it with "unless you also need pagination, in which case use @BatchSize or a separate batch query."

### 7. You can read EXPLAIN ANALYZE output

Most developers run EXPLAIN ANALYZE and don't know what to look for. You can spot `Seq Scan` on a large table, identify `Rows Removed by Filter` as an index-missing signal, and recognize when estimated rows vs actual rows diverge significantly (stale statistics — run `ANALYZE`).

### 8. You understand the connection pool / transaction interaction

Long `@Transactional` methods that do I/O (HTTP calls, file reads) hold a database connection for the duration of the I/O wait. This starves the pool. The VaadVivaad `ScraperService.scrapeOrRefresh` has this issue — it calls `eCourtWebClient.fetchCaseHtml()` inside `@Transactional`. For MVP it's fine. In production with 100 concurrent scrapes, all 100 hold connections while waiting for eCourts HTTP responses. The fix: fetch HTML first (outside transaction), then open the transaction for the DB writes.

### 9. You understand projection vs entity semantics

Projections (interface or DTO) are not managed by Hibernate's session. There's no dirty checking, no lazy loading, no first-level cache. They're plain data transfer objects. For list endpoints, you almost always want projections. For single entity detail endpoints where you'll modify and save, you want the full entity. Knowing which to use when is a sign of architectural clarity.

### 10. You can articulate the OFFSET pagination problem quantitatively

"At page 5000 with a page size of 20, the database reads 100,000 rows and discards 99,980. Query time grows linearly with page depth regardless of indexes." This is the kind of concrete answer that signals production experience.

---

## Quick Reference Summary

```
N+1 Problem
├── Cause: Lazy-loaded @OneToMany accessed in a loop
├── Detection: show-sql + count repeated queries, or hibernate.generate_statistics
└── Fixes:
    ├── JOIN FETCH  — best for single-entity lookup, doesn't work with Pageable
    ├── @EntityGraph — same as JOIN FETCH but declarative, works with derived queries
    ├── @BatchSize  — best for paginated lists, uses IN clause batching
    └── DTO Projection — don't load the collection at all, only select needed columns

Indexes (VaadVivaad)
├── idx_users_email          — login query
├── idx_court_cases_cnr      — primary case lookup (redundant with UNIQUE)
├── idx_court_cases_status   — filter by case status
├── idx_hearings_case_id     — load hearings for a case (FK join)
├── idx_hearings_date        — date-range queries on hearing_date
├── idx_hearings_next_date   — scheduler query (V7, most operationally critical)
├── idx_subscriptions_user_id — user's subscriptions
├── idx_subscriptions_case_id — case's subscribers
├── idx_notification_logs_hearing — notifications per hearing
└── idx_notification_logs_status  — retry job (WHERE status = 'FAILED')

Performance Checklist
□ open-in-view=false                          ✅ VaadVivaad has this
□ @Transactional(readOnly=true) on reads      ✅ VaadVivaad has this
□ default_batch_fetch_size set                ❌ Not set yet — add this
□ @Modifying with clearAutomatically          ⚠️  deleteAllByCaseId exists but lacks clearAutomatically
□ Projections for list endpoints              ❌ listCases returns full CaseResponse from entity
□ Connection pool sized correctly             ❌ No HikariCP config in application.yml yet
□ show-sql=false in production                ✅ Not set in base config (dev profile concern)
```

---

*VaadVivaad codebase — Spring Boot 3.4.4 / Java 21 / PostgreSQL / HikariCP / Hibernate 6.x*
*Prepared for senior Java/Spring Boot interview — JPA Performance & Hibernate*
