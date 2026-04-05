# RabbitMQ & Async Messaging — Senior Interview Prep
## VaadVivaad: Spring Boot 3.4.4 / Java 21

> **Mental model:** You have used `setTimeout` in Node to defer slow work so the
> current request can return immediately. RabbitMQ is that same idea — but across
> processes, across machines, and with durability guarantees. The message survives
> a server restart. The consumer can be a completely separate service. That is the
> leap from async-in-process to async-in-distributed-systems.

---

## Table of Contents

1. [Why Message Queues](#1-why-message-queues)
2. [AMQP Protocol](#2-amqp-protocol)
3. [Exchange Types](#3-exchange-types)
4. [Queues, Bindings, Routing Keys](#4-queues-bindings-routing-keys)
5. [Dead Letter Queue (DLQ)](#5-dead-letter-queue-dlq)
6. [@RabbitListener](#6-rabbitlistener)
7. [Message Acknowledgement](#7-message-acknowledgement)
8. [Jackson Message Converter](#8-jackson-message-converter)
9. [Separate Consumers for Separate Concerns](#9-separate-consumers-for-separate-concerns)
10. [Spring Application Events vs RabbitMQ](#10-spring-application-events-vs-rabbitmq)
11. [RabbitTemplate](#11-rabbittemplate)
12. [Concurrency and Thread Pools](#12-concurrency-and-thread-pools)
13. [Durability](#13-durability)
14. [The Transactional Outbox Gap](#14-the-transactional-outbox-gap)
15. [RabbitMQ Management UI](#15-rabbitmq-management-ui)
16. [Comparison with Alternatives](#16-comparison-with-alternatives)
17. [Node → Java Comparison](#17-node--java-comparison)
18. [Senior Interview Q&A](#18-senior-interview-qa)
19. [Senior Differentiators](#19-senior-differentiators)

---

## 1. Why Message Queues

### The Node mental model you already have

In Node, you have written code like this:

```javascript
app.post('/subscribe', async (req, res) => {
  await db.save(subscription);
  res.json({ success: true });           // respond immediately

  // Defer the slow part — don't make the user wait
  setImmediate(async () => {
    await sendWelcomeEmail(subscription); // can take 500ms-2s
  });
});
```

`setImmediate` defers work to the next iteration of the event loop. The HTTP
response is already sent. The email sends "in the background."

But `setImmediate` has a fatal flaw for production: if the Node process crashes
after the response but before the email sends, the work is **silently lost**. No
retry. No record that it even happened.

### What RabbitMQ adds

RabbitMQ is `setImmediate` for distributed systems — with three improvements:

1. **Durability** — the message is written to disk. Process crash? RabbitMQ
   restarts and the message is still there.
2. **Decoupling** — the producer and consumer don't need to be in the same
   process, or even the same language.
3. **Backpressure** — if the consumer is slow, messages queue up instead of
   overwhelming it. The producer never slows down.

### VaadVivaad's concrete use case

When a scraper finishes parsing a court hearing from eCourts:

```
ScraperService.scrapeOrRefresh()
    │
    ├── saves CourtCase to PostgreSQL      ← fast, must complete
    ├── saves Hearing to PostgreSQL        ← fast, must complete
    │
    └── rabbitTemplate.convertAndSend(     ← fire and move on
            SUMMARY_EXCHANGE,
            SUMMARY_ROUTING_KEY,
            new SummaryRequestEvent(hearing.getId(), cnrNumber)
        )
```

The Claude API call to generate an AI summary takes 2-5 seconds. If ScraperService
waited for it, a case with 10 hearings would add 20-50 seconds to the scrape. With
RabbitMQ, the scrape finishes in milliseconds and the summaries generate
independently in the background.

---

## 2. AMQP Protocol

### What it is

**AMQP** (Advanced Message Queuing Protocol) is a binary, open-standard wire
protocol for message brokers. It defines exactly how bytes travel between producer,
broker, and consumer. Because it is a standard, clients in Java, Python, Go, and
Node can all communicate with the same RabbitMQ broker.

The key analogy: HTTP is a protocol that any browser and any server can use. AMQP
is a protocol that any AMQP client library and any AMQP broker can use. RabbitMQ is
the most popular AMQP broker. Spring AMQP is the Java client.

### The three-part model

```
Producer ──► Exchange ──► Queue ──► Consumer
              (routing)   (storage)  (processing)
```

**Why three parts? Why not just Producer → Queue → Consumer?**

This is the design insight that separates RabbitMQ from simpler queues like SQS.

The Exchange is the routing layer. It decides *which* queues receive a message
based on rules. The Queue is just storage — dumb, ordered, FIFO. Separating routing
from storage gives you flexibility without complexity in the producer.

The producer says: "Here is a message for exchange `vaadvivaad.exchange` with
routing key `subscription.created`." It does not know or care which queues receive
it. The exchange figures that out. You can add new queues and bind them to the
exchange later — zero changes to the producer.

---

## 3. Exchange Types

RabbitMQ has four exchange types. Understanding when to use each is a senior-level
signal.

### Direct Exchange — route by exact routing key

A message goes to a queue only if its routing key **exactly matches** the binding
key. This is VaadVivaad's primary pattern.

```
Exchange: vaadvivaad.exchange (Direct)
│
├── routing key "subscription.created" ──► notification.queue
├── routing key "hearing.reminder"     ──► notification.queue
└── routing key "subscription.created.dlq" ──► notification.dlq
```

**When to use it:** Task routing where you know exactly which worker type should
handle which message. "Subscription events go to notification consumers. Summary
requests go to AI consumers."

### Topic Exchange — routing key patterns

Routing keys support wildcards:
- `*` matches one word
- `#` matches zero or more words

```
Exchange: events.exchange (Topic)

Routing key pattern "order.#"    → catches order.created, order.paid, order.shipped
Routing key pattern "*.error.*"  → catches db.error.timeout, api.error.auth
```

**When to use it:** You want flexible subscriptions. A logging consumer might
bind to `#` (everything). An error alert consumer might bind to `*.error.*`.

### Fanout Exchange — broadcast to all bound queues

Routing keys are ignored. Every bound queue gets every message.

```
Exchange: broadcast.exchange (Fanout)

ALL bound queues receive the message:
├── audit.queue
├── cache.invalidation.queue
└── analytics.queue
```

**When to use it:** Cache invalidation, real-time dashboards, WebSocket push to
all connected clients. Any "notify everyone" pattern.

### Headers Exchange — route by message headers (rare)

Routes by metadata in the message header, not the routing key. Almost never used in
practice. Useful when routing logic cannot be expressed as a string key.

### VaadVivaad's two exchanges from `RabbitMQConfig.java`

```java
// Exchange 1: main application exchange — notifications and DLQ routing
public static final String EXCHANGE = "vaadvivaad.exchange";

@Bean
public DirectExchange exchange() {
    return new DirectExchange(EXCHANGE);
}

// Exchange 2: dedicated AI summary exchange
public static final String SUMMARY_EXCHANGE = "summary.exchange";

@Bean
public DirectExchange summaryExchange() {
    return new DirectExchange(SUMMARY_EXCHANGE, true, false);
    //                                          durable, auto-delete
}
```

Two exchanges, both Direct. The separation is conceptual — notification concerns
use `vaadvivaad.exchange`, AI concerns use `summary.exchange`. This makes
permissions, monitoring, and debugging cleaner.

---

## 4. Queues, Bindings, Routing Keys

### How the pieces connect

A **binding** is the registration that tells an exchange "messages with this
routing key should go to this queue." It is the glue between Exchange and Queue.

Without a binding, the exchange has nowhere to route the message. The message is
silently dropped (or returned to the producer if mandatory flag is set).

### VaadVivaad's complete queue topology

**Queue 1: notification.queue**

```java
@Bean
public Queue notificationQueue() {
    return QueueBuilder.durable(NOTIFICATION_QUEUE)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
            .build();
}
```

This queue receives two types of messages via two separate bindings:

```java
// Binding A: subscription events
@Bean
public Binding notificationBinding() {
    return BindingBuilder
            .bind(notificationQueue())
            .to(exchange())
            .with(ROUTING_KEY);  // "subscription.created"
}

// Binding B: hearing reminder events (from Redis scheduler)
@Bean
public Binding hearingReminderBinding() {
    return BindingBuilder
            .bind(notificationQueue())
            .to(exchange())
            .with(HEARING_REMINDER_ROUTING_KEY);  // "hearing.reminder"
}
```

Both routing keys fan into the same queue. `NotificationConsumer` handles both
message types by having two `@RabbitListener` methods on the same queue.

**Queue 2: notification.dlq**

```java
@Bean
public Queue deadLetterQueue() {
    return QueueBuilder.durable(DLQ).build();  // "notification.dlq"
}

@Bean
public Binding dlqBinding() {
    return BindingBuilder
            .bind(deadLetterQueue())
            .to(exchange())
            .with(DLQ_ROUTING_KEY);  // "subscription.created.dlq"
}
```

The DLQ is a plain durable queue. It has no dead-letter configuration of its own —
messages that land here stay here for manual inspection.

**Queue 3: summary.request.queue**

```java
@Bean
public Queue summaryRequestQueue() {
    return QueueBuilder.durable(SUMMARY_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", "")
                                               // "" = default exchange
            .withArgument("x-dead-letter-routing-key", SUMMARY_REQUEST_DLQ)
            .build();
}
```

Note the difference: the summary queue routes dead letters to the **default
exchange** (`""`), not to `summary.exchange`. The default exchange is a built-in
Direct exchange where routing key equals queue name. So DLQ routing key
`"summary.request.dlq"` routes to a queue literally named `"summary.request.dlq"`.

```java
@Bean
public Binding summaryBinding() {
    return BindingBuilder
            .bind(summaryRequestQueue())
            .to(summaryExchange())
            .with(SUMMARY_ROUTING_KEY);  // "summary.request"
}
```

### The flow in full

```
ScraperService.upsertCase()
    │
    └── rabbitTemplate.convertAndSend(
            "summary.exchange",       ← exchange name
            "summary.request",        ← routing key
            SummaryRequestEvent{...}  ← payload serialized to JSON
        )
            │
            ▼
    summary.exchange (DirectExchange)
            │
            │  binding: "summary.request" → summary.request.queue
            ▼
    summary.request.queue
            │
            ▼
    SummaryConsumer.handleSummaryRequest()
```

---

## 5. Dead Letter Queue (DLQ)

### What it is

A DLQ is a queue that receives messages that could not be processed. Think of it
as a poison-message bucket — messages land here when:

1. The consumer throws an exception (message is rejected/nacked)
2. The message exceeds a configured TTL (time-to-live) without being consumed
3. The queue is at capacity (x-max-length exceeded)

Without a DLQ, a failed message either disappears (if auto-ack) or stays in the
queue and blocks processing forever (if manual-ack with no rejection handling).
The DLQ gives you a place to inspect, debug, and replay failed messages.

### How VaadVivaad configures it

The configuration happens on the **source queue**, not the DLQ itself. Two queue
arguments tell RabbitMQ where to send failed messages:

```java
@Bean
public Queue notificationQueue() {
    return QueueBuilder.durable(NOTIFICATION_QUEUE)
            .withArgument("x-dead-letter-exchange", EXCHANGE)
            // When a message fails, route it via vaadvivaad.exchange
            .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
            // with routing key "subscription.created.dlq"
            .build();
}
```

The DLQ routing key `"subscription.created.dlq"` is bound to `notification.dlq`:

```java
@Bean
public Binding dlqBinding() {
    return BindingBuilder
            .bind(deadLetterQueue())        // notification.dlq
            .to(exchange())                 // vaadvivaad.exchange
            .with(DLQ_ROUTING_KEY);         // "subscription.created.dlq"
}
```

So the failure path is:

```
notification.queue (consumer throws) 
    │
    └─► vaadvivaad.exchange / "subscription.created.dlq"
            │
            └─► notification.dlq  (sits here for inspection)
```

### The summary queue uses a different DLQ pattern

```java
.withArgument("x-dead-letter-exchange", "")
.withArgument("x-dead-letter-routing-key", SUMMARY_REQUEST_DLQ)
// "summary.request.dlq"
```

`""` is the default exchange. In RabbitMQ, every queue is automatically bound to
the default exchange with the queue name as routing key. So routing key
`"summary.request.dlq"` routes directly to the queue named `"summary.request.dlq"`.
This is a shortcut — no need to explicitly bind the DLQ to an exchange.

### Why DLQ design matters in interviews

The interviewer wants to know you understand failure modes. A system with no DLQ
is a system that silently loses messages on error. The DLQ is your audit trail.
It is also how you implement manual retry — inspect the DLQ, fix the bug, re-queue.

---

## 6. @RabbitListener

### What it does

`@RabbitListener` is a method-level annotation that registers the method as a
message consumer for a specified queue. Spring AMQP handles everything surrounding
the method call:

- Opening the AMQP connection and channel
- Polling the queue for new messages
- Deserializing the JSON payload to the Java type declared in the method parameter
- Calling the method
- Acknowledging (or nacking) the message based on whether the method returned
  normally or threw

The developer writes the business logic. Spring handles the infrastructure.

### VaadVivaad's NotificationConsumer

```java
// notification/consumer/NotificationConsumer.java

@Component
public class NotificationConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
        log.info("=== SUBSCRIPTION EVENT RECEIVED ===");
        log.info("User  : {} ({})", event.userFullName(), event.userEmail());
        log.info("Case  : {} — {}", event.cnrNumber(), event.caseTitle());
        log.info("Sub ID: {}", event.subscriptionId());
        log.info("===================================");
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleHearingReminder(HearingReminderEvent event) {
        log.info("=== HEARING REMINDER EVENT RECEIVED ===");
        log.info("User    : {} ({})", event.userFullName(), event.userEmail());
        log.info("Case    : {} — {}", event.cnrNumber(), event.caseDisplay());
        log.info("Hearing : {} for {}", event.hearingDate(), event.hearingPurpose());
        log.info("Phone   : {}", event.userPhone());
        log.info("=======================================");
    }
}
```

**How does Spring know which method to call for which message type?**

Spring AMQP inspects the message header `__TypeId__` (added automatically by
`Jackson2JsonMessageConverter` when publishing). When `SubscriptionCreatedEvent` is
published, the header is set to the fully-qualified class name. On consumption,
Spring matches the header to the method parameter type and routes to the correct
`@RabbitListener` method.

Both methods listen to the same `NOTIFICATION_QUEUE`. Spring AMQP uses the type
header to dispatch — effectively a type-based router built into the framework.

### VaadVivaad's SummaryConsumer

```java
// ai/consumer/SummaryConsumer.java

@Component
public class SummaryConsumer {

    private final SummaryService summaryService;

    public SummaryConsumer(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @RabbitListener(queues = RabbitMQConfig.SUMMARY_REQUEST_QUEUE)
    public void handleSummaryRequest(SummaryRequestEvent event) {
        log.info("Summary request received — hearing: {}, CNR: {}",
                event.hearingId(), event.cnrNumber());

        try {
            summaryService.generateAndSave(event.hearingId());
        } catch (Exception e) {
            // Catch here to prevent nack → DLQ for permanent failures.
            // Let the message be acked. DLQ stays clean for retryable failures.
            log.error("Failed to generate summary for hearing {}: {}",
                    event.hearingId(), e.getMessage(), e);
        }
    }
}
```

Note the try-catch inside the listener. This is a deliberate design choice
explained in the next section.

---

## 7. Message Acknowledgement

### The three modes

**AUTO (default)** — Spring acknowledges the message immediately after your method
returns normally. If the method throws, the message is nacked (negative
acknowledgement). A nacked message with a DLQ configured goes to the DLQ.

**MANUAL** — You control acknowledgement yourself via `Channel.basicAck()` or
`Channel.basicNack()`. Use this when you need to ack only after a downstream
confirmation (e.g., database write succeeded, external API call completed).

**NONE** — Fire and forget. Message is acked the moment it is delivered to the
consumer, before your code even runs. Fastest, but messages can be lost on crash.

### Default behavior in VaadVivaad

VaadVivaad does not configure acknowledgement mode explicitly, so it uses AUTO.
That means:

```
@RabbitListener method returns normally → Spring acks → message removed from queue
@RabbitListener method throws           → Spring nacks → message goes to DLQ
```

### The catch-block design in SummaryConsumer

```java
try {
    summaryService.generateAndSave(event.hearingId());
} catch (Exception e) {
    log.error("Failed to generate summary for hearing {}: {}",
            event.hearingId(), e.getMessage(), e);
    // Method returns normally despite the exception
    // → Spring ACKS the message
    // → Message does NOT go to DLQ
}
```

**Why catch instead of letting it propagate?**

Two categories of failure:

1. **Transient failures** (Claude API timeout, network blip) — should nack and
   go to DLQ for retry. Let the exception propagate.
2. **Permanent failures** (hearing ID no longer exists, data is corrupt) — retrying
   will never succeed. Filling the DLQ with unprocessable messages is noise.

By catching all exceptions and logging, the current implementation chooses to
never fill the DLQ. This is a tradeoff: you lose retry capability in exchange for
a clean DLQ. A more nuanced production design would catch only permanent failures
and re-throw transient ones.

### MANUAL ack example (for interview reference)

```java
@RabbitListener(queues = "my.queue", ackMode = "MANUAL")
public void handleMessage(MyEvent event,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag)
        throws IOException {
    try {
        process(event);
        channel.basicAck(tag, false);   // false = only ack this one message
    } catch (Exception e) {
        channel.basicNack(tag, false, false); // false, false = don't requeue → DLQ
    }
}
```

---

## 8. Jackson Message Converter

### Why you need it

By default, Spring AMQP uses Java serialization to turn objects into bytes. Java
serialization is:

- Not human-readable (binary format)
- Version-sensitive (any change to the class breaks deserialization)
- Java-only (your Python consumer cannot read it)
- A security risk (deserialization attacks)

`Jackson2JsonMessageConverter` replaces Java serialization with JSON. Messages
in RabbitMQ are now human-readable, language-agnostic, and forward-compatible
(adding nullable fields does not break consumers).

### VaadVivaad's configuration

```java
// config/RabbitMQConfig.java

@Bean
public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}

@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter());
    return template;
}
```

The converter is set on both the `RabbitTemplate` (for publishing) and on the
listener container (Spring AMQP picks up the `MessageConverter` bean automatically
for `@RabbitListener` methods when one is registered as a `@Bean`).

### What the message looks like on the wire

When `ScraperService` publishes a `SummaryRequestEvent`:

```java
new SummaryRequestEvent(
    UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
    "MHPN01-000123-2024"
)
```

The message body in RabbitMQ becomes:

```json
{
  "hearingId": "550e8400-e29b-41d4-a716-446655440000",
  "cnrNumber": "MHPN01-000123-2024"
}
```

And the message headers include:

```
content_type: application/json
__TypeId__: com.vaadvivaad.ai.event.SummaryRequestEvent
```

The `__TypeId__` header is how Spring AMQP knows which Java class to deserialize
into on the consumer side.

### Java records are perfect for events

VaadVivaad uses Java records for all event types:

```java
public record SummaryRequestEvent(
        UUID hearingId,
        String cnrNumber
) {}

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

public record HearingReminderEvent(
        UUID hearingId,
        UUID caseId,
        String cnrNumber,
        String caseDisplay,
        LocalDate hearingDate,
        String hearingPurpose,
        UUID userId,
        String userEmail,
        String userFullName,
        String userPhone
) {}
```

Records are immutable, have automatic `equals`, `hashCode`, and `toString`, and
Jackson serializes/deserializes them cleanly. They communicate intent: this is a
data carrier, not a mutable object.

---

## 9. Separate Consumers for Separate Concerns

### The problem with mixing fast and slow work

Imagine a single `AllEventsConsumer` with listeners for both
`notification.queue` and `summary.request.queue`. Spring AMQP runs each
`@RabbitListener` on its own thread pool, but by mixing concerns in one class you
are also likely to put them on the same queue — and a shared queue means a slow
message blocks fast ones.

### VaadVivaad's design

**NotificationConsumer** — lightweight:

```java
// Handles: SubscriptionCreatedEvent, HearingReminderEvent
// Work: log the event (eventually send WhatsApp/SMS via Twilio)
// Latency: < 10ms for logging, ~100-300ms for Twilio in future
// Queue: notification.queue
```

**SummaryConsumer** — heavyweight:

```java
// Handles: SummaryRequestEvent
// Work: calls Claude API, saves result to DB
// Latency: 2-5 seconds per message
// Queue: summary.request.queue
```

By having separate queues and separate consumers:

1. A Claude API timeout does not delay notification delivery by even a millisecond
2. Each consumer can be scaled independently (add more SummaryConsumer instances
   if Claude calls are the bottleneck, not NotificationConsumer)
3. Their DLQs are separate — a dead letter in AI processing does not pollute the
   notification DLQ

### The comment in SummaryConsumer says it directly

```java
/*
 * WHY @RabbitListener on a separate consumer class instead of
 * putting this in the existing NotificationConsumer?
 *
 * NotificationConsumer handles time-sensitive reminders.
 * SummaryConsumer handles AI calls that can take 2-5 seconds each.
 *
 * If they shared a consumer, a slow Claude response would block
 * reminder processing. Separate consumers process their queues
 * independently.
 *
 * INTERVIEW: "How do you prevent slow consumers from blocking fast ones?"
 * "Separate queues, separate consumers, separate thread pools."
 */
```

---

## 10. Spring Application Events vs RabbitMQ

### Two event systems, different purposes

| Feature | Spring ApplicationEvent | RabbitMQ |
|---------|------------------------|----------|
| Scope | In-process (same JVM) | Cross-process (distributed) |
| Transport | Method call | Network + broker |
| Default execution | Synchronous | Asynchronous |
| Durability | None (lost on crash) | Durable queues survive restart |
| Ordering | In order | FIFO per queue |
| When to use | Decoupling within one service | Communication between services or background jobs |

### VaadVivaad uses both — and which path each takes

In `SubscriptionService.subscribe()`, the publication goes **directly to RabbitMQ**
via `RabbitTemplate` — not through Spring Application Events:

```java
// subscription/service/SubscriptionService.java

@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    // ... validation, save subscription ...

    Subscription saved = subscriptionRepository.save(subscription);

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

    // Direct publish to RabbitMQ — no Spring ApplicationEvent in the middle
    rabbitTemplate.convertAndSend(
            RabbitMQConfig.EXCHANGE,
            RabbitMQConfig.ROUTING_KEY,   // "subscription.created"
            event
    );

    return toResponse(saved, courtCase);
}
```

In `ScraperService.upsertCase()`, the publication also goes directly:

```java
rabbitTemplate.convertAndSend(
        RabbitMQConfig.SUMMARY_EXCHANGE,
        RabbitMQConfig.SUMMARY_ROUTING_KEY,
        event
);
```

### The hybrid pattern (used in many production systems)

Some systems use Spring Application Events as a first hop and then publish to
RabbitMQ from the event listener. This pattern looks like:

```java
// Service — publishes an in-process Spring event
@Transactional
public void subscribe(SubscriptionRequest req) {
    Subscription saved = subscriptionRepository.save(subscription);
    applicationEventPublisher.publishEvent(
        new SubscriptionCreatedEvent(saved.getId(), ...)
    );
}

// Event listener — handles the Spring event and bridges to RabbitMQ
@Component
public class SubscriptionEventBridge {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
    }
}
```

The key here is `@TransactionalEventListener(phase = AFTER_COMMIT)`. This solves
the transactional outbox gap discussed in section 14.

VaadVivaad simplifies by publishing directly. The trade-off is that the transactional
outbox gap exists — but for an MVP, this is acceptable.

---

## 11. RabbitTemplate

### What it is

`RabbitTemplate` is Spring AMQP's main class for publishing messages. It is the
producer-side abstraction. It handles:

- Connection and channel management (uses a connection pool internally)
- Serialization via the configured `MessageConverter`
- Setting message headers (content type, type ID)
- The actual AMQP `basic.publish` command

Think of it as Spring's `RestTemplate` or `JdbcTemplate` — a convenient wrapper
that removes boilerplate.

### The key method

```java
rabbitTemplate.convertAndSend(String exchange, String routingKey, Object message);
```

- `exchange` — which exchange to publish to
- `routingKey` — the routing key (exchange uses this to decide which queues get the message)
- `message` — your Java object; `MessageConverter` serializes it to JSON

### VaadVivaad's RabbitTemplate configuration

```java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter());
    return template;
}
```

`ConnectionFactory` is auto-configured by Spring Boot when you add the
`spring-boot-starter-amqp` dependency. The connection details come from
`application-dev.yml`:

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: vaadvivaad
    password: vaadvivaad123
```

Spring Boot auto-configures the `ConnectionFactory`, channel pool, and connection
recovery. You inject `RabbitTemplate` anywhere you need to publish:

```java
// In any @Service, @Component, @Scheduled method:
@Autowired
private RabbitTemplate rabbitTemplate;

// Or via constructor injection (VaadVivaad style):
public ScraperService(RabbitTemplate rabbitTemplate, ...) {
    this.rabbitTemplate = rabbitTemplate;
}
```

---

## 12. Concurrency and Thread Pools

### Default behavior

By default, each `@RabbitListener` gets one consumer thread. That means messages
are processed one at a time (sequentially) per listener.

For `SummaryConsumer` where each Claude API call takes 2-5 seconds, a single
consumer thread means:
- Message 1 received at T=0, processed by T=3s
- Message 2 starts at T=3s
- With 10 pending summaries: 10 × 3s = 30s total queue drain time

### Adding concurrency

```java
@RabbitListener(queues = RabbitMQConfig.SUMMARY_REQUEST_QUEUE, concurrency = "3-10")
public void handleSummaryRequest(SummaryRequestEvent event) {
    // Now 3-10 threads process messages in parallel
    summaryService.generateAndSave(event.hearingId());
}
```

`concurrency = "3-10"` means:
- **3** base consumers always active (minimum concurrency)
- **10** maximum consumers (scales up under load)
- Spring AMQP creates additional consumer threads when the queue has depth

### The SimpleMessageListenerContainer

Behind `@RabbitListener` is a `SimpleMessageListenerContainer`. You can configure
it programmatically for more control:

```java
@Bean
public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        MessageConverter messageConverter) {

    SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(messageConverter);
    factory.setConcurrentConsumers(3);
    factory.setMaxConcurrentConsumers(10);
    factory.setPrefetchCount(1); // Fetch one message at a time per consumer
    return factory;
}
```

### Prefetch count — the hidden performance lever

`setPrefetchCount(1)` means each consumer thread pulls one message at a time from
the broker. Without this, RabbitMQ sends a burst of messages to the consumer —
and if the consumer is slow (like Claude API calls), messages pile up in the
consumer's in-memory buffer instead of being distributed to other consumers.

Prefetch = 1 with concurrency > 1 is the correct combination for slow consumers:
each thread handles exactly one message, and RabbitMQ distributes work fairly.

---

## 13. Durability

### Two types of durability

**Durable queues** — survive a RabbitMQ broker restart. The queue definition
(its name, arguments, bindings) is written to disk by the broker.

**Persistent messages** — survive a broker restart. The message body and headers
are written to disk by the broker. Requires the queue to also be durable.

Without both, a broker restart loses everything in memory.

### VaadVivaad's durability configuration

Every queue in VaadVivaad is declared with `QueueBuilder.durable(...)`:

```java
// All four queues:
QueueBuilder.durable(NOTIFICATION_QUEUE)  // notification.queue
QueueBuilder.durable(DLQ)                 // notification.dlq
QueueBuilder.durable(SUMMARY_REQUEST_QUEUE)  // summary.request.queue
QueueBuilder.durable(SUMMARY_REQUEST_DLQ)    // summary.request.dlq
```

When Spring AMQP publishes via `RabbitTemplate`, messages are sent as persistent
by default (`MessageProperties.PERSISTENT` delivery mode = 2). So both halves of
durability are covered.

### The `DirectExchange` constructor parameters

```java
new DirectExchange(SUMMARY_EXCHANGE, true, false);
//                                   ^     ^
//                                   |     auto-delete (delete when no consumers)
//                                   durable (survives restart)
```

Exchanges also have durability. A durable exchange with durable queues and persistent
messages gives you full crash safety.

---

## 14. The Transactional Outbox Gap

### The problem

`SubscriptionService.subscribe()` is annotated `@Transactional`. Inside the same
method, it publishes to RabbitMQ:

```java
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    Subscription saved = subscriptionRepository.save(subscription); // DB write
    
    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);   // MQ publish
    
    return toResponse(saved, courtCase);
}
```

**What happens if the DB transaction rolls back after the message is already sent?**

The message has been published to RabbitMQ. The consumer receives it. It tries to
look up the subscription that was just rolled back. The subscription does not exist.
Error. DLQ. Confusion.

This is the **transactional outbox gap**: your message queue and your database are
two separate systems. There is no global transaction spanning both.

The `ScraperService` has a comment acknowledging this exactly:

```java
/*
 * WHY inside @Transactional?
 * If RabbitMQ publish fails, the transaction rolls back and
 * we don't have orphaned hearings with no summary request.
 * If the transaction rolls back after publish — the consumer
 * will try to fetch a non-existent hearing ID and log a warning.
 * That is acceptable for MVP. Production would use transactional
 * outbox pattern to guarantee exactly-once delivery.
 */
```

### Solution 1: @TransactionalEventListener(phase = AFTER_COMMIT)

Use Spring's `@TransactionalEventListener` with `AFTER_COMMIT` phase. The event
is only published to RabbitMQ after the DB transaction has committed successfully:

```java
// Step 1: Publish a Spring in-process event inside the transaction
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    Subscription saved = subscriptionRepository.save(subscription);
    
    // This Spring event is only published to listeners AFTER commit
    applicationEventPublisher.publishEvent(
        new SubscriptionCreatedEvent(saved.getId(), ...)
    );
    return toResponse(saved, courtCase);
}

// Step 2: Bridge to RabbitMQ only after DB commits
@Component
public class SubscriptionEventBridge {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
        // This runs AFTER the DB transaction commits
        // Safe to publish: the subscription row exists in DB
        rabbitTemplate.convertAndSend(
            RabbitMQConfig.EXCHANGE,
            RabbitMQConfig.ROUTING_KEY,
            event
        );
    }
}
```

**Remaining gap:** what if the process crashes between DB commit and RabbitMQ
publish? The message is still lost. For 99.9% of applications, this is acceptable.

### Solution 2: Transactional Outbox Pattern (for 99.999%)

The outbox pattern eliminates even the crash window. Instead of publishing directly
to RabbitMQ, write the message to an `outbox` table in the **same DB transaction**:

```sql
CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exchange VARCHAR(255),
    routing_key VARCHAR(255),
    payload JSONB,
    published_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
```

```java
@Transactional
public SubscriptionResponse subscribe(SubscriptionRequest request) {
    subscriptionRepository.save(subscription);
    
    // Write to outbox in same transaction — atomic!
    outboxRepository.save(new OutboxMessage(
        EXCHANGE, ROUTING_KEY, toJson(event)
    ));
    
    return toResponse(saved, courtCase);
}
```

A separate scheduled job (the "relay") polls the outbox table and publishes
unprocessed messages to RabbitMQ:

```java
@Scheduled(fixedDelay = 1000)  // every second
@Transactional
public void relayOutboxMessages() {
    List<OutboxMessage> pending = outboxRepository.findByPublishedAtIsNull();
    for (OutboxMessage msg : pending) {
        rabbitTemplate.convertAndSend(msg.getExchange(), msg.getRoutingKey(), 
                                      msg.getPayload());
        msg.setPublishedAt(LocalDateTime.now());
        outboxRepository.save(msg);
    }
}
```

Now DB write and message record are atomic. The relay may publish duplicates on
crash-restart (at-least-once delivery), but the consumer can handle duplicates
via idempotency keys.

**VaadVivaad's decision:** Use the simple approach (direct publish inside
`@Transactional`) for MVP. The comment in `ScraperService` explicitly acknowledges
the gap. This is a correct engineering judgment — don't over-engineer for
a court case tracker MVP.

---

## 15. RabbitMQ Management UI

### Accessing it locally

The Management UI runs on `http://localhost:15672`. VaadVivaad's development
credentials are in `application-dev.yml`:

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672       ← AMQP protocol port (broker ↔ app)
    username: vaadvivaad
    password: vaadvivaad123
    # Management UI: http://localhost:15672 (HTTP port, separate)
```

Log in with the same `vaadvivaad` / `vaadvivaad123` credentials.

### What to look at when debugging

**Queues tab:**

```
Queue Name            | Messages | Consumers | State
notification.queue    | 0        | 2         | running
notification.dlq      | 3        | 0         | idle     ← 3 failed messages!
summary.request.queue | 45       | 1         | running  ← backlog building up
summary.request.dlq   | 0        | 0         | idle
```

- `notification.dlq` has 3 messages → your NotificationConsumer is throwing
- `summary.request.queue` has 45 messages → Claude API is slow, consumer can't keep up

**Exchanges tab:**
- Verify your exchanges exist and have the correct type (Direct)
- Check bindings: click the exchange name → Bindings section shows which queues
  are bound with which routing keys

**Message rate graphs:**
- `Publish rate` vs `Deliver rate` — if publish exceeds deliver, your queue is
  growing. Add consumer concurrency.

**Publish a test message:**
1. Go to Exchanges → `vaadvivaad.exchange`
2. Publish message section → routing key: `subscription.created`
3. Set payload: `{"subscriptionId":"...", "userEmail":"test@test.com", ...}`
4. Hit Publish — watch the queue count increase and your consumer log

**Inspect DLQ messages:**
1. Go to Queues → `notification.dlq`
2. Get messages section → fetch 1 message
3. The full message body and headers are shown, including the original routing key
   and a header `x-death` that records why it died (exception name, timestamp,
   original queue)

---

## 16. Comparison with Alternatives

### RabbitMQ vs Kafka vs AWS SQS

| Dimension | RabbitMQ | Kafka | AWS SQS |
|-----------|----------|-------|---------|
| Protocol | AMQP (open standard) | Custom (Kafka protocol) | HTTP/AWS SDK |
| Model | Push (broker pushes to consumer) | Pull (consumer polls) | Pull |
| Message retention | Deleted after ack | Retained for days/weeks (log) | 4-14 days |
| Replay | No (acked = gone) | Yes (seek to offset) | No |
| Throughput | Moderate (50k-100k msg/s) | Very high (millions/s) | Moderate |
| Routing | Rich (exchange types) | Topic + partition key | None (FIFO optional) |
| Ordering | Per queue | Per partition | Per group (FIFO queue) |
| Complexity | Medium | High | Low (managed) |
| Best for | Task queues, RPC, complex routing | Event streaming, log aggregation, replay | Simple AWS-native async |

### When to use each

**Use RabbitMQ when:**
- You need complex routing (different message types to different consumers)
- Task queue semantics matter (each message processed by exactly one consumer)
- You want RPC patterns (request-reply over queues)
- Messages should be deleted after processing (no replay needed)
- Example: VaadVivaad — notifications, AI summaries, hearing reminders

**Use Kafka when:**
- High throughput: millions of events per second (clickstream, IoT)
- You need replay: "replay all orders from last Tuesday"
- Event sourcing: the log is the source of truth
- Multiple consumers independently reading the same stream
- Example: ride-sharing GPS tracking, financial transaction log, audit trail

**Use AWS SQS when:**
- You are already deep in AWS ecosystem
- You want zero operational overhead (no broker to manage)
- Simple async decoupling is enough
- Standard queue (at-least-once) or FIFO queue (exactly-once) covers your needs
- Example: thumbnail generation after S3 upload, email queue for SES

---

## 17. Node → Java Comparison

### Publishing a message

**Node (amqplib):**
```javascript
const amqp = require('amqplib');

const conn = await amqp.connect('amqp://localhost');
const channel = await conn.createChannel();

await channel.assertExchange('my.exchange', 'direct', { durable: true });
await channel.publish(
    'my.exchange',
    'my.routing.key',
    Buffer.from(JSON.stringify({ userId: '123', action: 'subscribe' })),
    { persistent: true, contentType: 'application/json' }
);
```

**Java (Spring AMQP / RabbitTemplate):**
```java
rabbitTemplate.convertAndSend(
    RabbitMQConfig.EXCHANGE,
    RabbitMQConfig.ROUTING_KEY,
    new SubscriptionCreatedEvent(...)  // automatic JSON serialization
);
```

Spring handles: connection lifecycle, channel management, serialization,
persistent flag, content-type header. You call one line.

### Consuming messages

**Node (amqplib) — manual ack:**
```javascript
const channel = await conn.createChannel();
channel.prefetch(1);

channel.consume('notification.queue', async (msg) => {
    if (!msg) return;
    try {
        const event = JSON.parse(msg.content.toString());
        await handleEvent(event);
        channel.ack(msg);           // manual ack on success
    } catch (err) {
        console.error(err);
        channel.nack(msg, false, false); // nack → DLQ
    }
});
```

**Java (Spring AMQP) — AUTO ack:**
```java
@RabbitListener(queues = "notification.queue")
public void handleEvent(SubscriptionCreatedEvent event) {
    // No ack code needed
    // Spring acks on normal return, nacks on exception
    processEvent(event);
}
```

### Task queues (bullmq vs Spring AMQP)

**Node — bullmq (Redis-backed):**
```javascript
import { Queue, Worker } from 'bullmq';

const queue = new Queue('summaries', { connection: redisConnection });
await queue.add('generate', { hearingId: '550e8400...' });

const worker = new Worker('summaries', async (job) => {
    await generateSummary(job.data.hearingId);
}, { connection: redisConnection });
```

**Java — Spring AMQP (RabbitMQ-backed):**
```java
// Publisher
rabbitTemplate.convertAndSend(SUMMARY_EXCHANGE, SUMMARY_ROUTING_KEY,
    new SummaryRequestEvent(hearingId, cnrNumber));

// Consumer
@RabbitListener(queues = SUMMARY_REQUEST_QUEUE)
public void handleSummaryRequest(SummaryRequestEvent event) {
    summaryService.generateAndSave(event.hearingId());
}
```

The concepts are identical — bullmq and Spring AMQP solve the same problem. The
primary difference: bullmq uses Redis as the queue backend (no separate broker
needed), while Spring AMQP uses RabbitMQ (a dedicated message broker with richer
routing capabilities).

---

## 18. Senior Interview Q&A

**Q1: What is the difference between a Direct exchange and a Topic exchange?
When would you choose Topic over Direct?**

A Direct exchange routes a message to a queue when the message's routing key
exactly matches the binding key. Topic exchange supports wildcards: `*` matches
one word, `#` matches zero or more. VaadVivaad uses Direct because the routing
keys are fixed and known at configuration time: `subscription.created`,
`hearing.reminder`, `summary.request`. You would switch to Topic if consumers
needed flexible subscriptions — for example, a logging service binding to `#`
(all messages) while an alert service binds only to `*.error.*`.

---

**Q2: What happens when a @RabbitListener throws an exception?**

With the default AUTO acknowledgement mode, Spring AMQP intercepts the exception,
sends a NACK (negative acknowledgement) to the broker, and the message is rejected.
If the queue has `x-dead-letter-exchange` and `x-dead-letter-routing-key`
configured, the rejected message is routed to the DLQ via that exchange. If no DLQ
is configured, the message is dropped. VaadVivaad's `notification.queue` and
`summary.request.queue` both have DLQ configuration, so failed messages land in
`notification.dlq` and `summary.request.dlq` respectively for inspection.

---

**Q3: Why send just the hearingId in SummaryRequestEvent instead of the full
Hearing object?**

Three reasons. First, smaller payload — UUIDs are 36 bytes, a full Hearing object
could be kilobytes. Second, schema safety — if the Hearing class changes after a
message is in the queue, deserialization breaks. Sending IDs makes the event
schema stable regardless of entity changes. Third, freshness — the consumer
re-fetches from the database when processing, guaranteeing it acts on the latest
data rather than a snapshot taken at publish time. This is the "event notification"
pattern versus "event-carried state transfer."

---

**Q4: What is the transactional outbox problem and how would you fix it in
VaadVivaad?**

`SubscriptionService.subscribe()` saves to PostgreSQL and publishes to RabbitMQ
inside a `@Transactional` method. The DB and MQ are separate systems — there is no
distributed transaction spanning both. If the process crashes after the DB commits
but before `convertAndSend()` returns, the message is lost. Conversely, if
`convertAndSend()` succeeds but then the DB transaction rolls back (due to a later
exception), the consumer receives a message for a subscription that no longer exists.

The fix for the first problem: `@TransactionalEventListener(phase = AFTER_COMMIT)`.
Publish to RabbitMQ only after the DB commit is confirmed. For the second: use the
transactional outbox pattern — write the message to an `outbox_messages` table in
the same DB transaction, then have a relay job read and publish it. This makes the
write atomic and eliminates the ghost-message problem.

---

**Q5: Why does VaadVivaad use two separate exchanges (vaadvivaad.exchange and
summary.exchange) instead of one?**

Separation of concerns at the infrastructure level. The notification exchange handles
user-facing events (subscription confirmations, hearing reminders). The summary
exchange handles AI processing. Separate exchanges mean:
- Separate permissions (you could restrict which services can publish to each)
- Separate monitoring (message rates per exchange)
- Separate DLQ routing strategies (notification DLQ goes via vaadvivaad.exchange,
  summary DLQ goes via the default exchange)
- Clear conceptual boundary between notification domain and AI domain

---

**Q6: How would you scale VaadVivaad's SummaryConsumer if Claude API calls were
becoming a bottleneck?**

Three levels of scaling:

1. **Increase concurrency within one instance:** Add `concurrency = "3-10"` to the
   `@RabbitListener` annotation. Spring AMQP creates up to 10 consumer threads,
   processing up to 10 Claude calls in parallel per JVM instance. Also set
   `prefetchCount = 1` so messages distribute evenly across threads.

2. **Run multiple application instances:** Start multiple instances of the Spring
   Boot app. RabbitMQ distributes messages round-robin across all connected
   consumers automatically. No code changes needed — RabbitMQ's competing consumers
   pattern handles this.

3. **Separate the AI service:** Extract `SummaryConsumer` and `SummaryService` into
   a dedicated microservice. Scale that service independently without scaling the
   main VaadVivaad app.

---

**Q7: What is prefetch count and why does it matter for SummaryConsumer?**

Prefetch count (QoS — Quality of Service) controls how many unacknowledged messages
RabbitMQ sends to a consumer at once. Without prefetch, if you have 100 messages
and 3 consumer threads, RabbitMQ might send all 100 to the first thread that
connects. Threads 2 and 3 sit idle.

With `prefetchCount = 1`, each thread gets one message, acknowledges it, then gets
the next. Messages are distributed fairly across all consumer threads. For
SummaryConsumer where each message takes 2-5 seconds, prefetch = 1 + concurrency = 3
means three Claude API calls run simultaneously, with the next message queued to a
thread as soon as it finishes — optimal throughput without hoarding.

---

**Q8: What is the difference between AUTO and MANUAL acknowledgement? When would
you choose MANUAL?**

AUTO: Spring acks the message after your method returns normally. Exception → nack.
MANUAL: You call `channel.basicAck()` or `channel.basicNack()` yourself.

Choose MANUAL when the action that determines success is not "did my method return."
Example: you consume a message and write to an external system. The write is
asynchronous. With AUTO ack, the message is acked before the write completes. If the
write fails, the message is lost. With MANUAL ack, you ack only after the write
confirms success. Another example: batch processing — ack the batch after all items
in the batch are processed, not after each individual message.

For VaadVivaad's consumers (log the event, call Claude API), AUTO is correct —
method return corresponds to work done.

---

**Q9: Why do VaadVivaad's queue declarations appear in Spring's application context
rather than being created manually in RabbitMQ?**

The `@Bean`-annotated `Queue`, `Exchange`, and `Binding` declarations in
`RabbitMQConfig` are Spring AMQP "AMQP Admin" declarations. On startup, Spring AMQP
uses `RabbitAdmin` (auto-configured with Spring Boot) to declare all queues,
exchanges, and bindings via the AMQP API. If they already exist in RabbitMQ, the
declaration is a no-op. If they don't exist (fresh RabbitMQ instance), they are
created.

This is "infrastructure as code" for message broker topology. The broker state is
derived from code, not maintained manually. In production you might disable
auto-declaration and use infrastructure-as-code tools (Terraform, Ansible) to manage
RabbitMQ — the Spring beans are still useful for documentation and local dev.

---

**Q10: How does Jackson2JsonMessageConverter know which Java class to deserialize
into on the consumer side?**

When publishing, `Jackson2JsonMessageConverter` adds a `__TypeId__` header to the
AMQP message. The value is the fully-qualified class name of the published object:

```
__TypeId__: com.vaadvivaad.ai.event.SummaryRequestEvent
```

On the consumer side, when Spring AMQP receives a message for a `@RabbitListener`
method, it reads this header and uses Jackson to deserialize the JSON body into the
declared parameter type. If the header type and the parameter type match (or are
compatible), deserialization succeeds.

This is why producer and consumer must have the same class name and package, or you
need to configure type mapping. For internal VaadVivaad (same codebase), this is
automatic. For cross-service messaging, you would configure
`DefaultClassMapper` with type mappings to avoid coupling services by class name.

---

## 19. Senior Differentiators

These are the things that separate someone who has "used RabbitMQ" from someone who
understands it at a design level.

### 1. You understand why the Exchange exists

Most developers think "exchange = unnecessary complexity, why not just publish
directly to the queue?" The exchange enables the producer to be decoupled from
topology. The producer publishes to an exchange with a routing key. Which queues
receive the message is the broker's concern, configured by operations teams or
infrastructure code — not by the producer. Adding a new consumer queue requires
zero changes to the producer. That is the architectural value.

### 2. You know the transactional outbox problem without being asked

Mentioning `@TransactionalEventListener(phase = AFTER_COMMIT)` and the outbox
pattern proactively — not as a textbook answer but as "here is what VaadVivaad does
and here is the gap we knowingly accepted and why" — signals real production
experience.

### 3. You can talk about failure modes, not just the happy path

- What happens if RabbitMQ is down when SubscriptionService tries to publish?
  (`RabbitTemplate` throws `AmqpException`, the `@Transactional` method rolls back,
  the subscription is not saved. User gets a 500. Correct failure mode — no ghost
  subscription.)
- What happens if the consumer is down when a message arrives?
  (Message sits in the queue. When consumer restarts, it processes the backlog. This
  is RabbitMQ's fundamental value proposition.)
- What happens if the consumer processes the same message twice?
  (Idempotency. The consumer must be designed to handle duplicates safely.)

### 4. You discuss message design deliberately

VaadVivaad's `SummaryRequestEvent` comment explains the "event notification" vs
"event-carried state transfer" distinction. Sending IDs not state. Knowing this
vocabulary and the trade-offs signals that you have thought about event schema design
at scale.

### 5. You know prefetch count

This is a common production footgun. Default prefetch is often 250 — fine for fast
consumers, catastrophic for slow ones. One thread holds 250 in-flight messages while
other threads wait. Mentioning prefetch = 1 for slow consumers without being prompted
is a strong signal.

### 6. You can explain the DLQ as a tool, not just a safety net

The DLQ is not just "where messages go when they fail." It is an operational tool.
You can:
- Count DLQ depth to detect consumer bugs (if DLQ grows, something is wrong)
- Replay DLQ messages after a bug fix (shovel plugin or manual re-queue)
- Set up alerts on DLQ depth (CloudWatch, Prometheus)
- Inspect DLQ message headers to see the original routing key and exception

### 7. You understand competing consumers vs exclusive consumers

By default, `@RabbitListener` creates competing consumers — multiple instances of
the same consumer class all pull from the same queue, each getting a different
message. This is horizontal scaling. Exclusive consumers (one at a time, others
wait) are used for ordered processing. VaadVivaad uses competing consumers
implicitly — deploy two instances of the Spring Boot app and both process messages.

### 8. VaadVivaad-specific talking point

The scheduler design shows mature architecture thinking:

```
06:00 AM — reScrapeTrackedCases()    (ScraperService.scrapeOrRefresh)
08:00 AM — sendHearingReminders()    (NotificationScheduler)
```

Scrape runs 2 hours before reminders. This is intentional: if the scrape runs at
the same time as reminders, and a court reschedules a hearing this morning, the
reminder would have stale data. By running scrape first, reminders always use
fresh data. The comment in `NotificationScheduler` explains this — and being able
to articulate this design reasoning in an interview is a differentiator.

---

*File: `interview_prep/05_rabbitmq_messaging.md` — VaadVivaad Spring Boot 3.4.4 / Java 21*
*Covers: AMQP, exchanges, DLQ, @RabbitListener, acknowledgement, Jackson converter,*
*transactional outbox, concurrency, durability, Node comparisons, 10 Q&A pairs.*
