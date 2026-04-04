package com.vaadvivaad.subscription.event;

import java.time.LocalDateTime;
import java.util.UUID;

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