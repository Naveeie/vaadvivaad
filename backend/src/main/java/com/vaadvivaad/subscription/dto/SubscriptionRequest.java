package com.vaadvivaad.subscription.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubscriptionRequest(

        @NotNull(message = "Case ID is required")
        UUID caseId
) {}