// ai/event/SummaryRequestEvent.java

package com.vaadvivaad.ai.event;

import java.util.UUID;

/*
 * WHY hearingId and not the full Hearing object?
 *
 * RabbitMQ messages are serialized to JSON and stored in the queue.
 * Sending the full Hearing object means:
 * 1. Larger message payload
 * 2. If Hearing entity changes, old messages in queue fail to deserialize
 * 3. The consumer re-fetches from DB anyway to get the latest data
 *
 * Just send the ID. The consumer fetches fresh data when it processes.
 * This is the standard pattern for event-driven systems —
 * events carry identity, not state.
 *
 * INTERVIEW: "What is the difference between event-carried state transfer
 * and event notification?"
 * This is event notification — minimal payload, consumer fetches state.
 * Event-carried state transfer would include the full hearing data.
 * We use notification here because freshness matters more than
 * avoiding the extra DB read.
 */
public record SummaryRequestEvent(
        UUID hearingId,
        String cnrNumber   // for logging only, not used in processing
) {}