# 03 — JPA, Entities, Repositories & Flyway
## VaadVivaad Interview Prep — Spring Boot 3.4.4 / Java 21

> **Mental model first:** JPA is not magic. It is a *translation layer* between your Java objects
> and your relational database rows. Everything it does — queries, relationships, auditing — can be
> reduced to SQL you could write yourself. Understanding *what SQL JPA generates* is the difference
> between a junior who uses it and a senior who controls it.

---

## Table of Contents
1. [The ORM Stack: JPA vs Hibernate vs Spring Data JPA](#1-the-orm-stack)
2. [@Entity and @Table — Mapping a Class to a Table](#2-entity-and-table)
3. [Relationship Annotations — @OneToMany, @ManyToOne, @ManyToMany](#3-relationship-annotations)
4. [@JoinColumn — Owning Side vs mappedBy Non-Owning Side](#4-joincolumn-and-mappedby)
5. [Cascade — Making Lifecycle Operations Flow Downstream](#5-cascade)
6. [The Auditable Base Class — @MappedSuperclass and JPA Auditing](#6-the-auditable-base-class)
7. [Spring Data JPA Repositories — Zero Implementation, Full Power](#7-spring-data-jpa-repositories)
8. [Derived Query Methods — Spring Reads Your Method Names](#8-derived-query-methods)
9. [@Query (JPQL) — When Method Names Are Not Enough](#9-query-jpql)
10. [Pagination — Page\<T\> and Pageable](#10-pagination)
11. [@Transactional on Repositories](#11-transactional-on-repositories)
12. [Entity Lifecycle States — Transient, Persistent, Detached, Removed](#12-entity-lifecycle-states)
13. [Flyway — Schema Migration as Code](#13-flyway)
14. [ddl-auto: validate — The Correct Production Setting](#14-ddl-auto-validate)
15. [UUID as Primary Key — Tradeoffs](#15-uuid-as-primary-key)
16. [@Enumerated(EnumType.STRING) — Ordinal is a Trap](#16-enumerated-enumtypestring)
17. [Node.js → Java Comparison Table](#17-nodejs-to-java-comparison-table)
18. [Senior Interview Q&A — 10 Pairs](#18-senior-interview-qa)
19. [Senior Differentiators](#19-senior-differentiators)

---

## 1. The ORM Stack

### The Three Layers (and Why They're Separate)

Understanding this stack is crucial because interviewers will test whether you know which layer
does what, and what happens when you step outside Spring Data's abstractions.

```
Your Code
    │
    ▼
Spring Data JPA      ← HIGH-LEVEL ABSTRACTION
  (repositories, derived queries, pagination)
    │
    ▼
JPA (Jakarta Persistence API)   ← SPECIFICATION (just interfaces and annotations)
  (EntityManager, JPQL, entity lifecycle)
    │
    ▼
Hibernate            ← IMPLEMENTATION (the actual library that does the work)
  (SQL generation, session management, caching)
    │
    ▼
JDBC Driver          ← DATABASE WIRE PROTOCOL
    │
    ▼
PostgreSQL
```

**JPA is a specification.** It defines annotations (`@Entity`, `@Id`, `@OneToMany`), the
`EntityManager` interface, JPQL syntax, and the persistence lifecycle. JPA itself has no runnable
code — it is a contract.

**Hibernate is the implementation.** It reads your annotations, generates SQL, manages the
first-level cache (session), handles lazy loading proxies, and talks to JDBC. Spring Boot
auto-configures Hibernate as the JPA provider via `spring-boot-starter-data-jpa`.

**Spring Data JPA is the abstraction on top of JPA.** You declare an interface that extends
`JpaRepository<Entity, ID>` and Spring generates the implementation at startup using dynamic
proxies. You get 20+ methods for free, derived query generation from method names, JPQL with
`@Query`, and automatic transaction management.

### Node.js Analogy

| Java/Spring             | Node.js equivalent                                                  |
|-------------------------|---------------------------------------------------------------------|
| JPA (spec)              | Sequelize's TypeScript interfaces / ActiveRecord spec               |
| Hibernate               | Sequelize library itself (or TypeORM, MikroORM)                     |
| Spring Data JPA         | Prisma Client — an even higher abstraction that writes queries for you |
| EntityManager           | Sequelize's `Model.findOne()`, `Model.save()`, `sequelize.query()`  |
| JPQL                    | Sequelize's query builder object syntax (`{ where: { email: '...' } }`) |

The key difference: in Sequelize/Prisma, you call methods on model objects. In JPA, Spring Data
generates the entire repository class at startup — there is no `UserRepository.js` file with method
bodies. Spring writes it.

---

## 2. @Entity and @Table

### The Concept

When you annotate a class with `@Entity`, you are telling Hibernate: "instances of this class
represent rows in a database table." Hibernate will:
1. Register this class in the persistence context at startup
2. Validate (or generate) the corresponding table schema
3. Manage SELECT/INSERT/UPDATE/DELETE for this class

`@Table(name = "users")` maps the class to a specific table name. Without it, Hibernate uses the
class name as the table name (so `User` → table `user`, `CourtCase` → table `courtcase`). Explicit
naming is always better.

### VaadVivaad: User.java

```java
@Entity
@Table(name = "users")
public class User extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Subscription> subscriptions = new ArrayList<>();
}
```

**Breaking down each annotation:**

`@Id` — marks the primary key field. Required on every `@Entity`.

`@GeneratedValue(strategy = GenerationType.UUID)` — tells Hibernate to auto-generate a UUID before
INSERT. This is a Java-side generation strategy: Hibernate calls `UUID.randomUUID()` and assigns it
*before* sending the INSERT to the database. Compare to `GenerationType.IDENTITY` which relies on
the database's auto-increment and requires a SELECT after INSERT to get the ID back.

`@Column(nullable = false, unique = true)` — maps to `VARCHAR(255) NOT NULL UNIQUE` in SQL.
The `name` parameter is optional; if omitted, Spring converts `camelCase` to `snake_case` (so
`passwordHash` → `password_hash`). Explicitly naming columns (`name = "password_hash"`) is safer
because it decouples your Java field names from your column names.

`@Column(name = "password_hash", nullable = false)` — the `name` explicitly sets the column name,
avoiding any confusion between `passwordHash` and whatever Hibernate's naming strategy might produce.

### VaadVivaad: CourtCase.java Key Columns

```java
@Column(name = "cnr_number", nullable = false, unique = true)
private String cnrNumber;

@Column(columnDefinition = "TEXT")
private String petitioner;   // TEXT, not VARCHAR — no length limit

@Enumerated(EnumType.STRING)
@Column(nullable = false)
private CaseStatus status = CaseStatus.PENDING;
```

`columnDefinition = "TEXT"` — when you need to override the default type mapping entirely. Hibernate
maps `String` to `VARCHAR(255)` by default. `columnDefinition = "TEXT"` tells it to use PostgreSQL's
`TEXT` type for unlimited length content. This matches `TEXT` in V2 migration SQL.

### The No-Arg Constructor Rule

JPA requires a no-argument constructor. This is because Hibernate instantiates your entity using
reflection (`Class.newInstance()`) when hydrating results from a SQL query. It creates the empty
object first, then sets each field. Without `public User() {}`, Hibernate will throw at startup.

```java
// Required by JPA spec — Hibernate needs this to reconstruct entities from SQL rows
public User() {
}
```

In Node/Sequelize, this is invisible — Sequelize uses plain JavaScript objects. In JPA, the
framework controls object construction.

---

## 3. Relationship Annotations

### The Mental Model: Foreign Keys as Java References

In SQL, you model relationships with foreign key columns:
```sql
-- hearings has a case_id foreign key pointing to court_cases.id
case_id UUID NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE
```

In JPA, you model the same relationship with object references and annotations:
```java
// In Hearing.java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;   // Java reference instead of UUID case_id
```

JPA translates between these two worlds automatically. When you load a `Hearing`, Hibernate uses the
`case_id` column to know which `CourtCase` to potentially load.

### The Four Annotations

| Annotation     | SQL equivalent                  | Example in VaadVivaad                     |
|----------------|---------------------------------|-------------------------------------------|
| `@ManyToOne`   | FK column on the "many" table   | `Hearing.courtCase` → one CourtCase       |
| `@OneToMany`   | Inverse of ManyToOne            | `CourtCase.hearings` → list of Hearings   |
| `@ManyToMany`  | Junction table                  | Not used in VaadVivaad (Subscription acts as explicit junction) |
| `@OneToOne`    | FK with UNIQUE constraint       | Not used in VaadVivaad                    |

### FetchType: LAZY vs EAGER — The Most Important Decision

**EAGER** — "When you load the parent, immediately also load all children in the same SQL call."
```java
// EAGER example (don't do this by default)
@OneToMany(fetch = FetchType.EAGER)
private List<Hearing> hearings;
// Hibernate JOIN fetches all hearings every single time you load a CourtCase.
// If you load 50 cases, you get 50 cases × N hearings each = massive data.
```

**LAZY** — "When you access the parent, just load the parent row. Only load children when you
actually call getHearings()."
```java
// LAZY example (correct default)
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();
// FetchType defaults to LAZY for @OneToMany — no need to specify it
```

**The Golden Rule: Always LAZY by default.** EAGER loading is almost always a performance problem.
You pay the JOIN cost even when you don't need the children. Use `JOIN FETCH` in a specific `@Query`
when you know you need the children.

**VaadVivaad enforces LAZY everywhere:**
```java
// Hearing.java — ManyToOne is LAZY
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;

// Subscription.java — both sides LAZY
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;

// NotificationLog.java — all relationships LAZY
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "hearing_id", nullable = false)
private Hearing hearing;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;
```

Note: `@ManyToOne` defaults to `FetchType.EAGER` by JPA spec, but VaadVivaad explicitly overrides
this to `LAZY` on every `@ManyToOne`. This is correct production practice.

### CourtCase → Hearing Relationship (Both Sides)

```java
// In CourtCase.java (the "one" side)
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();

// In Hearing.java (the "many" side, owns the FK column)
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;
```

This is a **bidirectional relationship**. Both sides know about each other. From `CourtCase` you
can call `courtCase.getHearings()`. From `Hearing` you can call `hearing.getCourtCase()`. There is
still only ONE `case_id` column in the database — the relationship just has two Java entry points.

---

## 4. @JoinColumn and mappedBy

### The Owning Side: @JoinColumn

The entity that has `@JoinColumn` is the **owning side**. "Owning" means: this entity's table
contains the foreign key column. Hibernate looks at the owning side to determine how to write the FK
value on INSERT/UPDATE.

```java
// Hearing.java — OWNING SIDE
// The hearings table has a case_id column. Hearing "owns" the relationship.
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;
```

This maps directly to the `hearings.case_id` column in V3 migration:
```sql
case_id UUID NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE
```

### The Non-Owning Side: mappedBy

`mappedBy` on the `@OneToMany` side says: "I am not the side with the FK column. The FK is managed
by the `courtCase` field in the `Hearing` class. Don't generate a separate join table or column for
me — just navigate the relationship from the other side."

```java
// CourtCase.java — NON-OWNING SIDE
// No case_id column in court_cases table. This is just navigation.
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();
```

**Common mistake:** Forgetting `mappedBy` on the `@OneToMany` side. Without it, JPA creates a
*join table* (e.g., `court_case_hearings`) which is almost never what you want for a standard
parent-child relationship.

**Another common mistake:** Adding `@JoinColumn` on BOTH sides. That tells Hibernate both entities
own the FK, which leads to duplicate column errors or incorrect schema.

### The Subscription Entity: Both Sides Have @JoinColumn

```java
// Subscription.java — junction-like entity with two FK columns
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "case_id", nullable = false)
private CourtCase courtCase;
```

`Subscription` is the owning side of BOTH relationships because it holds both `user_id` and
`case_id` FK columns. This is why VaadVivaad uses an explicit `Subscription` entity rather than
`@ManyToMany` — it gives control over the composite unique constraint and the `notify_whatsapp` /
`notify_sms` preference columns. A `@ManyToMany` junction table cannot hold extra columns cleanly.

```java
// The unique constraint lives on the entity, enforcing one subscription per user-case pair
@Table(name = "subscriptions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "case_id"})
})
```

This matches V4 migration: `UNIQUE(user_id, case_id)`.

---

## 5. Cascade

### What Cascade Means

Cascade propagates **EntityManager operations** from parent to children. When you call
`entityManager.remove(courtCase)`, cascade determines whether that remove operation flows down to
all of `courtCase`'s hearings and subscriptions.

Without cascade, you would have to manually remove each child before removing the parent — or let
the database `ON DELETE CASCADE` handle it at the SQL level.

### CascadeType Options

| CascadeType | What it does                                                            |
|-------------|-------------------------------------------------------------------------|
| `PERSIST`   | When you save the parent, also save new children in the collection      |
| `MERGE`     | When you merge/update the parent, also merge/update its children        |
| `REMOVE`    | When you delete the parent, also delete its children                    |
| `REFRESH`   | When you refresh the parent from DB, also refresh children              |
| `DETACH`    | When you detach the parent from context, also detach children           |
| `ALL`       | All of the above — equivalent to `{PERSIST, MERGE, REMOVE, REFRESH, DETACH}` |

### VaadVivaad Uses CascadeType.ALL

```java
// CourtCase.java
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();

@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Subscription> subscriptions = new ArrayList<>();
```

`CascadeType.ALL` means: save, update, delete — all flow from `CourtCase` to its `Hearing` and
`Subscription` children automatically.

**`orphanRemoval = true`** — this is different from cascade REMOVE. `orphanRemoval` handles the
case where you *remove a child from the Java collection* rather than deleting the parent entirely:

```java
// This triggers orphanRemoval:
courtCase.getHearings().remove(hearingToRemove);
courtCaseRepository.save(courtCase);
// Hibernate detects the hearing is no longer in the list → generates DELETE for it

// CASCADE REMOVE triggers when:
courtCaseRepository.delete(courtCase);
// Hibernate deletes the case → cascade fires → deletes all hearings and subscriptions
```

### Entity Cascade vs SQL CASCADE: The Relationship

VaadVivaad uses BOTH — entity cascade for ORM operations AND SQL-level `ON DELETE CASCADE` in
migrations:

```sql
-- V3 migration (SQL level cascade)
case_id UUID NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE
```

```java
// CourtCase.java (JPA level cascade)
@OneToMany(mappedBy = "courtCase", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Hearing> hearings = new ArrayList<>();
```

They serve different layers. SQL `ON DELETE CASCADE` fires at the database level even if someone
deletes via raw SQL (bypassing JPA). JPA cascade fires when you use the ORM — it gives Hibernate
awareness of what's being deleted so it can fire lifecycle callbacks, update its first-level cache,
and avoid constraint violations.

**Interview point:** You don't need both, but having both is the safest approach. SQL cascade is
your safety net if anyone touches the DB directly. JPA cascade keeps Hibernate's in-memory state
consistent.

---

## 6. The Auditable Base Class

### The Problem It Solves

Every entity in VaadVivaad needs `created_at` and `updated_at` timestamps. Copying those fields and
their annotations into every entity is repetitive and error-prone. The solution is a shared base
class.

### @MappedSuperclass

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

`@MappedSuperclass` — this class is NOT an `@Entity`. It has no table of its own. Its annotated
fields are inherited by child entities and mapped into *their* tables. The `Auditable` class is
purely a mechanism for field inheritance. No `auditable` table is ever created.

`@EntityListeners(AuditingEntityListener.class)` — registers Spring Data's built-in listener that
intercepts JPA lifecycle events (`@PrePersist`, `@PreUpdate`) and automatically populates the
audit fields.

`@CreatedDate` + `updatable = false` — this field is set exactly once when the entity is first
persisted. The `updatable = false` column attribute means Hibernate never includes this column in
UPDATE statements, even if someone accidentally sets it again.

`@LastModifiedDate` — updated automatically every time the entity is merged/saved.

### What Extends Auditable

```java
// User.java — gets created_at and updated_at in the users table
public class User extends Auditable { ... }

// CourtCase.java — gets created_at and updated_at in the court_cases table
public class CourtCase extends Auditable { ... }
```

### Enabling Auditing: JpaAuditingConfig

```java
// config/JpaAuditingConfig.java
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
    // No methods needed. The annotation does everything.
}
```

`@EnableJpaAuditing` tells Spring to activate the `AuditingEntityListener`. Without this
annotation, `@CreatedDate` and `@LastModifiedDate` do nothing — the fields stay null.

**Why is this in a separate config class?** Spring Boot has some known conflicts between
`@EnableJpaAuditing` on the main application class and `@WebMvcTest` slices in tests. Putting it
in a dedicated `@Configuration` class avoids those test context loading issues.

### Node.js Analogy

In Sequelize, you can achieve this with `timestamps: true` in model options and Sequelize
automatically adds `createdAt` / `updatedAt`. The difference is Sequelize timestamps are
opt-in per-model, while VaadVivaad's `Auditable` is a shared abstract class that multiple entities
extend — more like a TypeORM `@CreateDateColumn` / `@UpdateDateColumn` on a base entity class.

---

## 7. Spring Data JPA Repositories

### The Core Insight: You Declare, Spring Implements

```java
// user/repository/UserRepository.java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

This is an **interface**. There is no `UserRepositoryImpl.java`. At application startup, Spring
Data JPA generates a dynamic proxy that implements this interface. The generated implementation:
- Uses Hibernate's `EntityManager` under the hood
- Wraps every method in a transaction (read-only for finds, read-write for saves)
- Applies the correct SQL based on method name parsing or `@Query` annotations

### What JpaRepository Gives You for Free

`JpaRepository<User, UUID>` provides 20+ methods immediately:

| Method                        | What it does                                              |
|-------------------------------|-----------------------------------------------------------|
| `findById(UUID id)`           | SELECT by PK, returns `Optional<User>`                    |
| `findAll()`                   | SELECT * FROM users                                       |
| `findAll(Pageable pageable)`  | SELECT with LIMIT/OFFSET + count query                    |
| `findAll(Sort sort)`          | SELECT with ORDER BY                                      |
| `save(User user)`             | INSERT if new (id is null/new), UPDATE if managed         |
| `saveAll(List<User> users)`   | Batch INSERT/UPDATE                                       |
| `deleteById(UUID id)`         | DELETE by PK                                              |
| `delete(User user)`           | DELETE the entity                                         |
| `existsById(UUID id)`         | SELECT COUNT(1) > 0                                       |
| `count()`                     | SELECT COUNT(*)                                           |
| `getReferenceById(UUID id)`   | Returns a proxy without SELECT (use when you only need the FK reference) |

### Node.js Analogy

This is like having a Sequelize/Prisma model where ALL standard CRUD operations are already defined:
```javascript
// You never write this in Sequelize — but it's approximately what Spring Data gives you:
const UserRepository = {
  findById: (id) => User.findByPk(id),
  findAll: () => User.findAll(),
  save: (user) => user.id ? user.save() : User.create(user),
  deleteById: (id) => User.destroy({ where: { id } }),
  existsById: (id) => User.count({ where: { id } }).then(c => c > 0),
  // ... 15 more methods
};
```

But Spring generates this at runtime — you write zero implementation code.

### The @Repository Annotation

`@Repository` marks this as a Spring-managed component (like `@Service` or `@Controller`). It also
enables Spring's persistence exception translation — Spring converts Hibernate/JDBC exceptions into
Spring's `DataAccessException` hierarchy, which is more meaningful and consistent.

---

## 8. Derived Query Methods

### How Spring Reads Method Names

Spring Data parses method names using keywords and field paths. The general pattern:

```
findBy[FieldName][Condition]And[FieldName][Condition]OrderBy[FieldName][Direction]
```

Spring splits this into tokens, maps each token to your entity's field names, and generates JPQL
(which Hibernate converts to SQL) at startup — not at runtime. If you make a typo, the application
fails to start. This is a significant advantage over raw query strings that only fail at runtime.

### VaadVivaad Examples

**UserRepository:**
```java
// findBy + Email → WHERE u.email = ?
Optional<User> findByEmail(String email);
// SQL: SELECT * FROM users WHERE email = ?

// existsBy + Email → SELECT COUNT(1) > 0 WHERE email = ?
boolean existsByEmail(String email);
// SQL: SELECT count(*) > 0 FROM users WHERE email = ?
```

**CourtCaseRepository:**
```java
// findBy + CnrNumber → WHERE cc.cnr_number = ?
Optional<CourtCase> findByCnrNumber(String cnrNumber);
// SQL: SELECT * FROM court_cases WHERE cnr_number = ?

boolean existsByCnrNumber(String cnrNumber);
```

**HearingRepository:**
```java
// findBy + CourtCase + Id → JOIN court_cases + WHERE case_id = ?
// OrderBy + HearingDate + Desc → ORDER BY hearing_date DESC
List<Hearing> findByCourtCaseIdOrderByHearingDateDesc(UUID caseId);
// SQL: SELECT * FROM hearings WHERE case_id = ? ORDER BY hearing_date DESC
```

**SubscriptionRepository:**
```java
// findBy + UserId → WHERE user_id = ?
List<Subscription> findByUserId(UUID userId);

// findBy + CourtCaseId → WHERE case_id = ?
List<Subscription> findByCourtCaseId(UUID caseId);

// findBy + UserId + And + CourtCaseId → WHERE user_id = ? AND case_id = ?
Optional<Subscription> findByUserIdAndCourtCaseId(UUID userId, UUID caseId);

boolean existsByUserIdAndCourtCaseId(UUID userId, UUID caseId);
```

**NotificationLogRepository:**
```java
// findBy + Status → WHERE status = ?
List<NotificationLog> findByStatus(NotificationStatus status);

// findBy + UserId → WHERE user_id = ?
List<NotificationLog> findByUserId(UUID userId);
```

### Keyword Reference

| Keyword in method name | SQL equivalent           |
|------------------------|--------------------------|
| `findBy`               | SELECT … WHERE           |
| `existsBy`             | SELECT COUNT > 0 WHERE   |
| `countBy`              | SELECT COUNT(*) WHERE    |
| `deleteBy`             | DELETE WHERE             |
| `And`                  | AND                      |
| `Or`                   | OR                       |
| `OrderByXDesc`         | ORDER BY x DESC          |
| `OrderByXAsc`          | ORDER BY x ASC           |
| `LessThan`             | < ?                      |
| `GreaterThan`          | > ?                      |
| `Between`              | BETWEEN ? AND ?          |
| `In`                   | IN (?)                   |
| `IsNull`               | IS NULL                  |
| `Containing`           | LIKE %?%                 |
| `StartingWith`         | LIKE ?%                  |

### Why `CourtCaseId` Works Without a `courtCaseId` Field

`Hearing` has a `courtCase` field of type `CourtCase`, not a `UUID courtCaseId` field. Spring Data
traverses the relationship: `CourtCase` → `id`. So `findByCourtCaseIdOrderByHearingDateDesc(UUID caseId)`
means: "navigate from Hearing to CourtCase, then match on CourtCase's `id` field."

This is a *property path traversal*, and it generates:
```sql
SELECT h.* FROM hearings h WHERE h.case_id = ? ORDER BY h.hearing_date DESC
```

---

## 9. @Query (JPQL)

### When Derived Methods Aren't Enough

Derived method names break down when you need:
- JOINs with specific fetch strategies
- Subqueries
- Aggregate functions (SUM, AVG, MAX)
- Batch UPDATE or DELETE
- DISTINCT projections across entities
- Complex OR/AND combinations that produce unreadable method names

That's when you use `@Query` with JPQL.

### JPQL vs SQL: The Critical Difference

**SQL** operates on *tables and columns*:
```sql
SELECT DISTINCT c.cnr_number FROM subscriptions s JOIN court_cases c ON s.case_id = c.id
```

**JPQL** operates on *entities and fields*:
```java
@Query("SELECT DISTINCT c.cnrNumber FROM Subscription s JOIN s.courtCase c")
```

Notice:
- `Subscription` (capitalized) — the Java class, not the `subscriptions` table
- `s.courtCase` — the Java field name, not the `case_id` column
- `c.cnrNumber` — the Java field name, not `cnr_number` column

Hibernate translates JPQL into SQL based on your entity mappings. This means if you rename a
column but keep the field name the same, your JPQL still works.

### VaadVivaad @Query Examples

**SubscriptionRepository — DISTINCT projection across a join:**
```java
@Query("SELECT DISTINCT c.cnrNumber FROM Subscription s JOIN s.courtCase c")
List<String> findAllDistinctCnrNumbers();
```

This navigates from the `Subscription` entity to its `courtCase` relationship, then projects just
the CNR number. The generated SQL:
```sql
SELECT DISTINCT cc.cnr_number FROM subscriptions s JOIN court_cases cc ON s.case_id = cc.id
```

**HearingRepository — JOIN FETCH for eager loading in a specific query:**
```java
@Query("SELECT h FROM Hearing h JOIN FETCH h.courtCase WHERE h.nextHearingDate = :date")
List<Hearing> findByNextHearingDate(@Param("date") LocalDate date);
```

`JOIN FETCH` forces Hibernate to load `h.courtCase` in the SAME SQL query even though the
relationship is lazy. This is how you *selectively* eager-load: keep the default LAZY, but use
`JOIN FETCH` in the specific query where you know you'll need the related entity. This prevents
the N+1 problem (see Senior Differentiators section).

The `:date` is a named parameter bound via `@Param("date")`.

**HearingRepository — @Modifying for batch DELETE:**
```java
@Modifying
@Query("DELETE FROM Hearing h WHERE h.courtCase.id = :caseId")
void deleteAllByCaseId(@Param("caseId") UUID caseId);
```

`@Modifying` is required for any `@Query` that modifies data (INSERT, UPDATE, DELETE). Without it,
Spring Data treats the query as a SELECT and throws an exception.

Why use this instead of the derived `deleteByCourtCaseId()`? The derived delete method:
1. First runs SELECT to load all matching entities
2. Then runs individual DELETE for each entity

`@Modifying @Query` generates a single bulk DELETE SQL:
```sql
DELETE FROM hearings WHERE case_id = ?
```

For 100 hearings, that's 1 query vs 101 queries. This is a genuine performance difference.

**@Modifying must run in a transaction.** Spring Data JPA's auto-transaction covers read queries,
but `@Modifying` methods require a write transaction. The `@Transactional` on the calling service
method covers this in VaadVivaad.

---

## 10. Pagination

### The Problem Pagination Solves

`findAll()` returns every row. For a court case listing with 10,000 cases, that means loading all
10,000 into memory. Pagination allows fetching one page at a time with metadata about total results.

### The Pageable + Page<T> Pattern

```java
// In your service or controller, you create a Pageable:
Pageable pageable = PageRequest.of(
    0,                          // page number (0-based)
    20,                         // page size
    Sort.by("createdAt").descending()
);

// JpaRepository.findAll(Pageable) is inherited — no code needed in repository
Page<CourtCase> page = courtCaseRepository.findAll(pageable);
```

`Page<T>` contains:
```java
page.getContent()        // List<CourtCase> — the actual rows for this page
page.getTotalElements()  // long — total rows across ALL pages (requires a COUNT query)
page.getTotalPages()     // int — total number of pages
page.getNumber()         // int — current page number (0-based)
page.getSize()           // int — page size
page.hasNext()           // boolean — is there a next page?
page.isFirst()           // boolean
page.isLast()            // boolean
```

Hibernate executes TWO queries: one for the data (`SELECT … LIMIT ? OFFSET ?`) and one for the
total count (`SELECT COUNT(*) …`). The count query is necessary to populate `getTotalPages()` and
`getTotalElements()`.

### Passing Pageable from a Controller

```java
// The controller receives page/size/sort as query parameters automatically
@GetMapping("/cases")
public Page<CaseResponse> getCases(
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "20") int size
) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
    return courtCaseRepository.findAll(pageable).map(CaseResponse::from);
}
// GET /cases?page=0&size=20
```

Alternatively, Spring MVC can auto-bind a `Pageable` parameter if you enable
`@EnableSpringDataWebSupport` — then `?page=0&size=20&sort=createdAt,desc` binds automatically.

### Node.js Analogy

```javascript
// Sequelize equivalent (you write this yourself):
const { count, rows } = await CourtCase.findAndCountAll({
  limit: 20,
  offset: page * 20,
  order: [['createdAt', 'DESC']]
});
// VaadVivaad's Spring Data gives you count + rows in one Page<T> object automatically
```

---

## 11. @Transactional on Repositories

### Spring Data JPA Auto-Transaction

Every method in a Spring Data JPA repository runs inside a transaction automatically. You do NOT
need to add `@Transactional` yourself for standard repository operations.

**Default behavior:**
- All write methods (`save`, `delete`, `saveAll`, `deleteAll`) — `@Transactional` with propagation
  `REQUIRED` (joins existing transaction or creates a new one)
- All read methods (`findById`, `findAll`, etc.) — `@Transactional(readOnly = true)`

### readOnly = true — What It Actually Does

`readOnly = true` is more than a hint. In Hibernate, it:
1. Disables **dirty checking** — Hibernate normally snapshots every entity on load and compares on
   flush to detect changes. `readOnly` skips this expensive snapshot, reducing memory overhead.
2. Signals to the database driver (and connection pool) that this transaction only reads, enabling
   optimizations like read-from-replica routing.
3. Hibernate does NOT flush the session before executing a read-only query.

### @Transactional in Service vs Repository

Service-level `@Transactional` is the correct place for business transactions that span multiple
repository calls:

```java
@Service
public class ScraperService {

    @Transactional   // One transaction wraps ALL of these operations
    public CourtCase upsertCase(ParsedCaseData data) {
        CourtCase courtCase = courtCaseRepository.findByCnrNumber(data.cnrNumber())
            .orElse(new CourtCase());

        // update fields...

        // This @Modifying query MUST be inside a transaction
        hearingRepository.deleteAllByCaseId(courtCase.getId());

        // New hearings are saved via cascade when courtCase is saved
        courtCaseRepository.save(courtCase);

        return courtCase;
    }
}
```

If the `save` at the end fails, the `deleteAllByCaseId` is rolled back. Without service-level
`@Transactional`, each repository call is its own transaction — deletion commits, then save fails,
leaving you with an orphaned state.

---

## 12. Entity Lifecycle States

### The Four States

Understanding these states explains several "confusing" JPA behaviors: why lazy loading fails
outside a transaction, why modifying a returned entity sometimes doesn't need `save()`, etc.

```
new User()              →  TRANSIENT
                            (not managed, not in DB)

entityManager.persist()  →  PERSISTENT (MANAGED)
  OR
repository.save(user)    →  PERSISTENT
                            (managed by the current EntityManager/session,
                             all changes auto-detected and synced on flush)

Transaction ends         →  DETACHED
  OR
entityManager.detach()   →  DETACHED
                            (still has an ID, exists in DB, but no longer
                             tracked by any EntityManager)

entityManager.remove()   →  REMOVED
  OR
repository.delete()      →  REMOVED
                            (will be deleted on next flush/commit)
```

### Why Lazy Loading Fails on Detached Entities

```java
// DANGER: Detached entity lazy load
@GetMapping("/cases/{id}/hearings")
public List<Hearing> getHearings(@PathVariable UUID id) {
    CourtCase courtCase = courtCaseRepository.findById(id).orElseThrow();
    // Transaction ends here (method completes, Spring Data's readOnly transaction closes)
    // courtCase is now DETACHED

    return courtCase.getHearings(); // LazyInitializationException!
    // Hibernate tries to load the lazy collection but there's no active EntityManager
}
```

**Solutions:**
1. Use `JOIN FETCH` in the query to load hearings eagerly for this specific query
2. Use `@Transactional` on the service method to keep the session open
3. Return a DTO that you map inside the transaction before the entity detaches
4. Use `spring.jpa.open-in-view=true` (anti-pattern — see Senior Differentiators)

VaadVivaad sets `open-in-view: false` in `application.yml`, which is correct. This means you must
be intentional about when lazy collections are accessed.

### Dirty Checking — The Magic of PERSISTENT State

```java
@Transactional
public void updateCaseStatus(UUID id, CaseStatus newStatus) {
    CourtCase courtCase = courtCaseRepository.findById(id).orElseThrow();
    // courtCase is PERSISTENT — Hibernate has a snapshot of its current state

    courtCase.setStatus(newStatus);
    // You do NOT need to call repository.save(courtCase)!
    // Hibernate detects the change on transaction flush and generates UPDATE automatically
}
```

This is **dirty checking**: Hibernate compares the entity's current state to its snapshot from when
it was loaded. Any differences become UPDATE statements on transaction commit. This is why in
service methods marked `@Transactional`, you can often just modify an entity and not call `save()`.

---

## 13. Flyway

### Why Not `ddl-auto: create`?

`ddl-auto: create` drops and recreates all tables on every startup. It destroys all data. Even
`ddl-auto: update` is dangerous in production — it can make destructive changes (like dropping a
column that still has data) or fail silently on complex schema changes.

The industry standard for production is: **Flyway manages schema, Hibernate only validates.**

### What Flyway Does

On application startup, Flyway:
1. Connects to the database
2. Reads a table called `flyway_schema_history` (it creates this on first run)
3. Compares which migration files have already run (tracked by version number + checksum)
4. Runs any new migrations in version order
5. Records each new migration in `flyway_schema_history`

This gives you a complete, auditable history of every schema change — who added what column, when,
in what order.

### The Naming Convention

```
V{version}__{description}.sql
  │               │
  │               └── Two underscores. Description can have spaces (use underscores).
  └── Integer version. Must be unique. Determines execution order.
```

Examples from VaadVivaad:
```
V1__create_users_table.sql
V2__create_court_cases_table.sql
V3__create_hearings_table.sql
V4__create_subscriptions_table.sql
V5__create_notification_logs_table.sql
V6__seed_sample_data.sql
V7__update_hearings_add_next_hearing_date.sql
V8__fix_seed_hearing_next_dates.sql
```

### Checksum Enforcement — The Immutability Rule

Once a migration file runs, Flyway stores a checksum of the file content in `flyway_schema_history`.
If you edit that file afterward, Flyway detects the checksum mismatch and **refuses to start the
application**.

This is intentional and correct behavior. It prevents:
- Developers "fixing" a migration after it ran in production (which would leave prod and dev out of sync)
- Accidental edits corrupting the schema audit trail

**The golden rule: Never edit a migration that has already run. Add a new migration.**

V7 demonstrates this correctly:
```sql
-- V7__update_hearings_add_next_hearing_date.sql
-- CORRECT: New migration instead of editing V3
ALTER TABLE hearings
    RENAME COLUMN detail TO notes;

ALTER TABLE hearings
    ADD COLUMN next_hearing_date DATE;

CREATE INDEX idx_hearings_next_date ON hearings(next_hearing_date);
```

If the original column name `detail` was a mistake caught after V3 was deployed, you fix it with V7
— not by editing V3.

### VaadVivaad Migrations: V1 Through V8 Explained

**V1 — `create_users_table.sql`**
Creates the `users` table with UUID PK (using `pgcrypto`'s `gen_random_uuid()`), email with UNIQUE
constraint, password hash, full name, phone, role as VARCHAR (maps to the `Role` enum stored as
STRING), and audit timestamps. Index on email for O(log n) login lookups.

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- enables gen_random_uuid()

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    ...
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
```

Note: The SQL `DEFAULT 'USER'` and the Java `private Role role = Role.USER` are redundant with each
other. Both set the same default from different layers. This is intentional belt-and-suspenders.

**V2 — `create_court_cases_table.sql`**
Creates the `court_cases` table. No FK columns yet — this table is the "one" side of
CourtCase → Hearing and CourtCase → Subscription relationships. Two indexes: one on `cnr_number`
for lookup by CNR (already UNIQUE, but explicit for documentation) and one on `status` for
filtering active vs disposed cases.

**V3 — `create_hearings_table.sql`**
Creates `hearings` with `case_id UUID REFERENCES court_cases(id) ON DELETE CASCADE`. This is the
"many" side. Originally uses column name `detail` (later renamed in V7). No `next_hearing_date`
yet (added in V7). Index on `case_id` for fetching all hearings for a case, index on `hearing_date`
for scheduler queries.

**V4 — `create_subscriptions_table.sql`**
Creates the `subscriptions` junction table with `user_id` and `case_id` FKs, both with
`ON DELETE CASCADE`. The `UNIQUE(user_id, case_id)` constraint enforces one subscription per
user-case pair at the database level — mirroring the `@UniqueConstraint` in `Subscription.java`.
Boolean notification preference columns with sensible defaults.

**V5 — `create_notification_logs_table.sql`**
Creates the audit trail for every notification attempt. `hearing_id` and `user_id` FKs with
`ON DELETE CASCADE`. Status as VARCHAR (maps to `NotificationStatus` enum). `sent_at` is nullable
because it's only set when the notification actually sends. Index on `status` for the retry
scheduler that polls for FAILED/PENDING notifications.

**V6 — `seed_sample_data.sql`**
Inserts 3 sample court cases (Chennai civil suit, Coimbatore criminal case, Madurai disposed MCOP)
with 2-3 hearings each. Uses hardcoded UUIDs for predictable reference in dev/test. All insertions
use `CURRENT_TIMESTAMP` for audit columns. Note that hearings at this point still use the `detail`
column (V7 hasn't run yet), and `next_hearing_date` does not exist yet.

**V7 — `update_hearings_add_next_hearing_date.sql`**
Demonstrates the correct way to evolve schema after initial deployment:
1. `RENAME COLUMN detail TO notes` — preserves existing seed data, doesn't null anything out
2. `ADD COLUMN next_hearing_date DATE` — nullable by default, safe to add to existing rows
3. `CREATE INDEX idx_hearings_next_date` — for scheduler queries that filter by next hearing date

This migration is also why `Hearing.java` has `@Column(name = "notes")` instead of `detail` —
the entity was updated to match the renamed column.

**V8 — `fix_seed_hearing_next_dates.sql`**
Updates the seed data to give at least one hearing a `next_hearing_date = CURRENT_DATE + 1`. This
is a dev convenience — the `NotificationScheduler` queries by `nextHearingDate` and without this,
it would never find any seed hearings to process. The subquery finds the most recently created
hearing and sets its date to tomorrow.

```sql
UPDATE hearings
SET next_hearing_date = CURRENT_DATE + INTERVAL '1 day'
WHERE id = (
    SELECT h.id FROM hearings h
    JOIN court_cases c ON h.case_id = c.id
    ORDER BY h.created_at DESC
    LIMIT 1
);
```

### Flyway Configuration in application.yml

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

`classpath:db/migration` maps to `src/main/resources/db/migration/` in the JAR. Flyway reads all
`V*.sql` files from this location on startup.

---

## 14. ddl-auto: validate

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

### What Each ddl-auto Value Does

| Value        | Behavior                                                    | Use case          |
|--------------|-------------------------------------------------------------|-------------------|
| `none`       | Hibernate does nothing with schema                          | Production with Flyway |
| `validate`   | Hibernate checks entities match DB schema, throws if not    | Production with Flyway (VaadVivaad uses this) |
| `update`     | Hibernate adds missing columns/tables, never drops          | Dangerous in prod |
| `create`     | Drops all tables, recreates from entities                   | Local dev only (destroys data) |
| `create-drop`| Creates on startup, drops on shutdown                       | Test suites only  |

### Why `validate` is Correct When Using Flyway

`validate` means Hibernate will:
1. Read your entity annotations (`@Entity`, `@Column`, etc.)
2. Compare them against the actual database schema
3. If there's a mismatch (e.g., entity has a `nextHearingDate` field but the `next_hearing_date`
   column doesn't exist in the DB), throw a `SchemaManagementException` and refuse to start

This is a safety net. If someone adds a field to an entity but forgets to write a Flyway migration,
the application fails to start immediately — in development, not in production at 3am when the
first query hits the missing column.

The correct prod pattern: Flyway runs first (on startup), creates/alters schema, then Hibernate
validates. They work together.

### open-in-view: false

```yaml
spring:
  jpa:
    properties:
      open-in-view: false
```

`open-in-view: true` (the old Spring Boot default) keeps the EntityManager open for the entire
HTTP request lifecycle — through the controller and even the view rendering. This means lazy
collections can be loaded even after the service method returns. Sounds convenient; it's a trap.

**Why it's bad:**
- Database transactions held open across serialization (JSON conversion), which can take seconds
- Unpredictable lazy loading making debugging query counts impossible
- Hides N+1 problems that only appear in production under load

`open-in-view: false` forces you to be explicit: load everything you need inside the service
`@Transactional` boundary, map to DTOs, and return. VaadVivaad correctly sets this to false.

---

## 15. UUID as Primary Key

### Why VaadVivaad Uses UUIDs Everywhere

```java
// Every entity in VaadVivaad
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```

**Advantages:**

1. **No sequence contention** — with integer sequences (`SERIAL` / `AUTO_INCREMENT`), every INSERT
   requires a lock on the sequence to get the next number. Under high write load, this is a
   bottleneck. UUID generation is independent on each JVM thread.

2. **Safe to expose in URLs** — `/cases/a1b2c3d4-...` doesn't reveal how many cases exist
   (unlike `/cases/1234` which tells attackers there are at least 1234 cases).

3. **ID generated before INSERT** — `GenerationType.UUID` generates the UUID in Java before sending
   the INSERT. This means you have the ID immediately, without needing a SELECT after INSERT (unlike
   `IDENTITY` strategy which requires a round-trip to get the DB-generated auto-increment value).

4. **Merge-safe for distributed systems** — if you're generating IDs on multiple servers or
   merging data from multiple databases, UUID collisions are astronomically unlikely.

**Disadvantages:**

1. **B-tree index fragmentation** — integer PKs are sequential, so inserts go to the end of the
   index B-tree (fast). UUIDs are random, so inserts scatter across the B-tree, causing page splits
   and fragmentation over time. This can degrade write performance at very large scales.

2. **Storage size** — UUID is 16 bytes vs 4 bytes for an int. In a table with millions of rows and
   indexes referencing the PK, this matters.

3. **Human unfriendliness** — `a1b2c3d4-e5f6-7890-abcd-ef1234567890` is hard to type and reference
   in debugging compared to `1234`.

**Mitigations:** UUID v7 (time-ordered UUIDs, available in Java 21+) addresses fragmentation by
making UUIDs monotonically increasing while still being globally unique. VaadVivaad uses standard
random UUID v4, which is fine for its scale.

---

## 16. @Enumerated(EnumType.STRING)

### The Problem with EnumType.ORDINAL

```java
// DANGEROUS — never use this
@Enumerated(EnumType.ORDINAL)
private CaseStatus status;
```

`ORDINAL` stores the enum's position (0, 1, 2...) in the database:
```java
enum CaseStatus {
    PENDING,   // stored as 0
    ACTIVE,    // stored as 1
    DISPOSED   // stored as 2
}
```

If you later insert a new value:
```java
enum CaseStatus {
    PENDING,   // stored as 0
    CLOSED,    // stored as 1 ← INSERTED HERE
    ACTIVE,    // stored as 2 ← was 1, now 2!
    DISPOSED   // stored as 3 ← was 2, now 3!
}
```

All existing rows with value `1` (previously meaning `ACTIVE`) now silently mean `CLOSED`. Data
corruption with no error. This has caused production incidents in real systems.

### The Fix: EnumType.STRING

```java
// VaadVivaad's correct approach
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private CaseStatus status = CaseStatus.PENDING;
```

Stores `"PENDING"`, `"ACTIVE"`, `"DISPOSED"` as VARCHAR. Adding a new enum value anywhere in the
enum definition has zero impact on existing database rows. The string representation is stable.

**Tradeoff:** Slightly more storage than ordinal. Irrelevant in practice for status columns.

### Where VaadVivaad Uses This

```java
// User.java
@Enumerated(EnumType.STRING)
private Role role = Role.USER;           // stores "USER" or "ADMIN"

// CourtCase.java
@Enumerated(EnumType.STRING)
private CaseStatus status = CaseStatus.PENDING;  // stores "PENDING", "ACTIVE", "DISPOSED"

// NotificationLog.java
@Enumerated(EnumType.STRING)
private NotificationChannel channel;     // stores "WHATSAPP" or "SMS"

@Enumerated(EnumType.STRING)
private NotificationStatus status = NotificationStatus.PENDING; // stores "PENDING", "SENT", "FAILED"
```

All match the SQL column type `VARCHAR(50)` / `VARCHAR(20)` in migrations.

---

## 17. Node.js to Java Comparison Table

| Concept                  | Node.js / JavaScript                          | Java / Spring                                    |
|--------------------------|-----------------------------------------------|--------------------------------------------------|
| ORM Library              | Sequelize, Prisma, TypeORM, MikroORM          | Hibernate (via Spring Data JPA)                  |
| Model definition         | `sequelize.define('User', { ... })`           | `@Entity @Table(name="users") class User {}`     |
| Primary key              | `id: { type: DataTypes.UUID, primaryKey: true }` | `@Id @GeneratedValue(strategy = GenerationType.UUID)` |
| Column definition        | `email: { type: DataTypes.STRING, unique: true }` | `@Column(nullable = false, unique = true)`       |
| Enum column              | `role: DataTypes.ENUM('USER', 'ADMIN')`       | `@Enumerated(EnumType.STRING) private Role role` |
| One-to-many              | `User.hasMany(Subscription)` + `Subscription.belongsTo(User)` | `@OneToMany(mappedBy="user")` + `@ManyToOne @JoinColumn` |
| Save entity              | `user.save()` or `User.create(data)`          | `repository.save(user)`                          |
| Find by PK               | `User.findByPk(id)`                           | `repository.findById(id)` → `Optional<User>`     |
| Find by field            | `User.findOne({ where: { email } })`          | `repository.findByEmail(email)` (derived)         |
| Custom query             | `sequelize.query('SELECT ...')`               | `@Query("SELECT ... JPQL ...")` on repository    |
| Migrations               | Sequelize CLI migrations, Prisma migrate       | Flyway (`V1__desc.sql` files)                    |
| Migration versioning     | Timestamp-based filenames, `SequelizeMeta` table | Version-prefixed filenames, `flyway_schema_history` |
| Schema from entities     | `sequelize.sync()` (dangerous in prod)        | `ddl-auto: create` (dangerous in prod)            |
| Auditing (timestamps)    | `timestamps: true` on model                   | `@MappedSuperclass Auditable` + `@EnableJpaAuditing` |
| Pagination               | `{ limit: 20, offset: 0 }` manual             | `PageRequest.of(page, size)` → `Page<T>` with total count |
| Transaction              | `sequelize.transaction(t => { ... })`         | `@Transactional` on service method               |
| Lazy loading             | Default in Sequelize (no eager by default)    | Default `LAZY` for `@OneToMany`, must be explicit on `@ManyToOne` |
| Eager loading            | `include: [{ model: Hearing }]` in query      | `JOIN FETCH` in `@Query` or `FetchType.EAGER`    |

---

## 18. Senior Interview Q&A

**Q1: What is the difference between JPA, Hibernate, and Spring Data JPA? What does each layer own?**

JPA is the Jakarta Persistence specification: a set of annotations (`@Entity`, `@Id`, `@OneToMany`)
and interfaces (`EntityManager`, `EntityManagerFactory`) with no runtime code. Hibernate is the
implementation that provides actual SQL generation, session management, lazy loading proxies, and
first-level caching. Spring Data JPA sits above both: it takes your `JpaRepository` interface,
introspects method names at startup, and generates Hibernate-backed implementations using dynamic
proxies. You own the entity design and the repository interface; Spring owns the generated SQL.

---

**Q2: Why is `FetchType.EAGER` on a `@ManyToOne` the default in JPA spec, but VaadVivaad overrides
it to LAZY everywhere?**

The JPA spec defaults `@ManyToOne` to EAGER because the assumption was "if you load a child, you
probably want its parent." This assumption is wrong in practice. With EAGER, every `Hearing` load
also fetches its `CourtCase` — even when you only need the hearing date for a scheduler job. Across
100 hearings that's 100 unnecessary JOINs. VaadVivaad overrides to `FetchType.LAZY` on all
`@ManyToOne` relationships and uses `JOIN FETCH` in `@Query` only where the related entity is
actually needed (e.g., `findByNextHearingDate` JOINs FETCH `h.courtCase` because the notification
message needs the CNR number).

---

**Q3: What does `mappedBy` do and what happens if you forget it?**

`mappedBy` on the `@OneToMany` side declares that this side does NOT own the foreign key column —
the FK is managed by the field named in `mappedBy` on the other entity. Without `mappedBy`, JPA
assumes both sides own a FK, which would require a join table (Hibernate generates something like
`court_case_hearings` with `court_case_id` and `hearing_id`). This is wrong for a standard
parent-child relationship where the child table holds the FK column. The presence of `mappedBy`
removes the join table and tells Hibernate "use the `case_id` column in `hearings`, managed by
`Hearing.courtCase`."

---

**Q4: Explain the difference between `CascadeType.ALL` and `orphanRemoval = true`. Can you have one
without the other?**

`CascadeType.ALL` propagates EntityManager operations from parent to children — persist, merge,
remove, refresh, detach. When you delete a `CourtCase`, cascade REMOVE fires and deletes its
`Hearing` children. `orphanRemoval = true` handles a different scenario: when you remove a child
from the Java collection (`courtCase.getHearings().remove(hearing)`) without deleting the parent.
Hibernate detects the child is no longer referenced by any parent in the collection, marks it as an
"orphan," and deletes it on flush. You can have `CascadeType.REMOVE` without `orphanRemoval` (bulk
parent delete cascades, but collection removal doesn't), and you can have `orphanRemoval = true`
without `CascadeType.ALL` (collection removal cascades, but other operations don't). VaadVivaad uses
both on every `@OneToMany`.

---

**Q5: Why does VaadVivaad's `HearingRepository` use `@Modifying @Query` for delete instead of the
derived `deleteByCourtCaseId()`?**

Spring Data's derived delete method is syntactic sugar that first loads all matching entities
(SELECT), then deletes each one individually (N DELETE statements). For a case with 10 hearings,
that's 11 queries. `@Modifying @Query("DELETE FROM Hearing h WHERE h.courtCase.id = :caseId")`
generates a single bulk DELETE SQL. The tradeoff: Hibernate doesn't fire `@PostRemove` lifecycle
events because it doesn't load the entities first. In VaadVivaad's case, there are no lifecycle
callbacks on `Hearing`, so the bulk approach is correct and significantly more efficient.

---

**Q6: What is `@MappedSuperclass` and why is `Auditable` not just a regular `@Entity`?**

`@MappedSuperclass` marks a class whose fields are mapped into the tables of its concrete subclass
entities — no table is created for the `Auditable` class itself. If `Auditable` were a regular
`@Entity`, JPA would create an `auditable` table and potentially use table inheritance (joined,
single-table, or per-class strategies), adding massive complexity for what is just a field-sharing
mechanism. `@MappedSuperclass` is purely a Java inheritance tool for column reuse — the
`created_at` and `updated_at` fields appear in the `users` and `court_cases` tables as if they were
declared there directly.

---

**Q7: What happens if you edit a Flyway migration file that has already been applied to a database?**

Flyway computes a CRC32 checksum of each migration file when it runs and stores it in
`flyway_schema_history`. On the next startup, Flyway recomputes the checksum of every already-run
migration and compares it to the stored value. If any checksum mismatches, Flyway throws
`FlywayValidateException` and the application refuses to start. This is intentional — it prevents
the dangerous scenario where someone "fixes" a migration in source control that has already executed
in prod, silently diverging the schema state from what the migration records claim. The correct fix
is always a new migration (`V9__...sql`) that alters the schema from its current state.

---

**Q8: Why is `spring.jpa.open-in-view=false` the correct setting, and what problem does it solve?**

`open-in-view: true` (old Spring Boot default) keeps the Hibernate `EntityManager` open for the
duration of the entire HTTP request — through the service layer, through controller, and through
JSON serialization. This allows lazy collections to be loaded during JSON serialization, which
sounds convenient but has serious problems: database connections are held open for the entire
serialization time, N+1 queries can be triggered invisibly during serialization making them
invisible in development, and it blurs the boundary between the persistence layer and the
presentation layer. `open-in-view: false` forces all data loading to happen inside a
`@Transactional` service method, making query behavior explicit and predictable. VaadVivaad sets
this to `false` and maps entities to DTOs inside service methods before the transaction closes.

---

**Q9: Explain the entity lifecycle states and give a practical example of why a "detached" entity
matters.**

Entities have four states: Transient (new Java object, no ID, not in DB), Persistent (managed by
an active EntityManager, all changes auto-detected), Detached (has an ID and exists in DB, but not
tracked by any EntityManager — typically after a transaction ends), and Removed (marked for
deletion). The detached state matters most in two scenarios: (1) lazy loading — if you access a
lazy collection on a detached entity, Hibernate throws `LazyInitializationException` because there
is no active session to issue the SELECT. (2) "is this a new object?" logic — `repository.save(entity)`
calls `entityManager.persist()` for transient entities and `entityManager.merge()` for detached
ones. Merge returns a NEW persistent copy; the detached instance you passed in is still detached.
This is why you should always use the return value of `save()`.

---

**Q10: How does Spring Data JPA know whether to INSERT or UPDATE when you call `repository.save(entity)`?**

Spring Data JPA's `SimpleJpaRepository.save()` uses the `isNew()` check from the `Persistable`
interface (or its own `EntityInformation`). For `GenerationType.UUID` entities in Spring Boot 3+,
if the `@Id` field is null, it's new (INSERT via `persist()`). If the `@Id` is already set (non-null),
Spring calls `entityManager.merge()`, which issues an UPDATE for managed entities or a SELECT then
UPDATE for detached entities. For VaadVivaad, since IDs are `UUID` generated by Java before INSERT,
the entity's ID is always populated before `save()` is called. Spring uses `Version` field presence
or checks whether the entity was previously fetched to determine persist vs merge. For truly new
entities (created with `new CourtCase()` without setting an ID), `GenerationType.UUID` sets the
ID inside `persist()` before the INSERT statement runs.

---

## 19. Senior Differentiators

These are the things that separate a candidate who *uses* JPA from one who *understands* it.

### The N+1 Query Problem

The most common JPA performance issue. It occurs when you load a list of entities and then access a
lazy collection on each:

```java
// N+1 PROBLEM:
List<CourtCase> cases = courtCaseRepository.findAll();  // 1 query
for (CourtCase c : cases) {
    c.getHearings().size();  // N queries (one per case)
}
// Total: 1 + N queries. For 100 cases: 101 queries.
```

**Fix with JOIN FETCH:**
```java
@Query("SELECT c FROM CourtCase c JOIN FETCH c.hearings")
List<CourtCase> findAllWithHearings();
// Total: 1 query with JOIN — all hearings loaded in one SQL statement
```

**Fix with EntityGraph:**
```java
@EntityGraph(attributePaths = {"hearings"})
List<CourtCase> findAll();  // Spring Data respects the EntityGraph hint
```

**The real fix:** Don't pass entities to your view/controller layer. Map to DTOs inside the
`@Transactional` service method, accessing only the fields you need. This eliminates accidental
lazy loading entirely.

### First-Level Cache (Session Cache)

Within a single transaction, Hibernate caches every entity by its primary key. If you call
`findById(id)` twice in the same transaction, the second call hits the cache — no second SQL query.
This is the "first-level cache" (session cache), and it is always on. You cannot disable it.

```java
@Transactional
public void demonstrateCache() {
    CourtCase c1 = courtCaseRepository.findById(someId).get();  // SELECT fires
    CourtCase c2 = courtCaseRepository.findById(someId).get();  // Cache hit — no SQL
    assert c1 == c2;  // Same Java object reference
}
```

The implication: `@Modifying @Query` bypasses the first-level cache — it goes directly to SQL
without loading entities. After a `@Modifying` delete, if you call `findById()` for a deleted
entity in the same transaction, the cache might return a stale result. Use
`@Modifying(clearAutomatically = true)` to invalidate the cache after a bulk operation.

### The Difference Between `save()` and `saveAndFlush()`

`save()` marks the entity as dirty and schedules changes. Hibernate decides when to actually execute
the SQL (usually at transaction commit or when a query needs a consistent view). `saveAndFlush()`
immediately writes the SQL to the database within the current transaction — useful when you need the
updated state to be visible to a subsequent `@Query` in the same transaction.

### Why Hibernate Generates an Extra SELECT on `@GeneratedValue(IDENTITY)`

With `GenerationType.IDENTITY` (database auto-increment), Hibernate cannot batch INSERT statements
because it needs the generated ID from the database after each INSERT to populate the entity's
`@Id` field. UUID strategy avoids this: Hibernate generates the UUID before INSERT, can batch
multiple INSERTs, and doesn't need a SELECT after each INSERT. This is one reason VaadVivaad uses
UUID — it enables Hibernate's JDBC batch optimization (`spring.jpa.properties.hibernate.jdbc.batch_size`).

### The Pitfall of Bidirectional Relationships

In a bidirectional relationship (`CourtCase` ↔ `Hearing`), you must maintain both sides in Java:

```java
// WRONG — only sets one side
hearing.setCourtCase(courtCase);
// courtCase.getHearings() does NOT contain this hearing

// CORRECT — set both sides
hearing.setCourtCase(courtCase);
courtCase.getHearings().add(hearing);
```

If you only set the owning side (`hearing.setCourtCase()`), the SQL FK column is set correctly,
but the in-memory Java model is inconsistent. Code that navigates from `courtCase.getHearings()`
won't see the new hearing until the session is refreshed. The best practice is to add a convenience
method to the parent entity:

```java
// Helper method in CourtCase.java (not in VaadVivaad, but senior-level pattern)
public void addHearing(Hearing hearing) {
    hearings.add(hearing);
    hearing.setCourtCase(this);  // maintains both sides
}
```

### Projections — Loading Only What You Need

When you only need a subset of fields (e.g., just `cnrNumber` and `status` for a list view),
loading the entire `CourtCase` entity wastes memory and bandwidth. Spring Data supports:

```java
// Interface projection — Spring generates a proxy
public interface CaseSummary {
    String getCnrNumber();
    CaseStatus getStatus();
    String getCourtName();
}

// In repository:
List<CaseSummary> findAllProjectedBy();
// SQL: SELECT cnr_number, status, court_name FROM court_cases
// NOT: SELECT * FROM court_cases
```

This is analogous to `SELECT cnrNumber, status FROM CourtCase` in JPQL and is significantly more
efficient for large datasets.

---

*File: `03_jpa_entities_repositories_flyway.md` — VaadVivaad Interview Prep Series*
*Last updated: April 2026 | Spring Boot 3.4.4 / Java 21 / PostgreSQL / Hibernate 6*
