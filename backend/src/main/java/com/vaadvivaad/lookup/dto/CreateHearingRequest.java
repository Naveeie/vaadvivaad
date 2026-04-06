package com.vaadvivaad.lookup.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record CreateHearingRequest(

    @NotNull(message = "Hearing date is required")
    LocalDate hearingDate,
    String purpose,
    String notes,
    LocalDate nextHearingDate
) {
}