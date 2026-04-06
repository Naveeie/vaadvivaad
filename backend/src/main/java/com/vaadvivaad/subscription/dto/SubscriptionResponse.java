package com.vaadvivaad.subscription.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionResponse(
        UUID subscriptionId,
        UUID caseId,
        String cnrNumber,
        String caseTitle,
        LocalDateTime subscribedAt
) {}