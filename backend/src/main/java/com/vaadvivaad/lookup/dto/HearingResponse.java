package com.vaadvivaad.lookup.dto;

import java.time.LocalDate;
import java.util.UUID;

public record HearingResponse(
    UUID id,
    LocalDate hearingDate,
    String purpose,
    String detail,
    String aiSummaryHindi
) {
}