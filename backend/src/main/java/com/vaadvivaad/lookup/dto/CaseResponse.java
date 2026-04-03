package com.vaadvivaad.lookup.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CaseResponse(
    UUID id,
    String cnrNumber,
    String caseType,
    String filingNumber,
    LocalDate filingDate,
    String registrationNumber,
    LocalDate registrationDate,
    String status,
    String petitioner,
    String respondent,
    String courtName,
    String judgeName,
    LocalDateTime lastScrapedAt,
    List<HearingResponse> hearings
) {
}