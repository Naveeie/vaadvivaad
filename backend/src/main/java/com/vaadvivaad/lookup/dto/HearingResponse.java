package com.vaadvivaad.lookup.dto;

import java.time.LocalDate;
import java.util.UUID;

public record HearingResponse(
    UUID id,
    LocalDate hearingDate,
    String purpose,
    String notes,              // was: detail
    LocalDate nextHearingDate, // new field
    String aiSummaryHindi
) {
}