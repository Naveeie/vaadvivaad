package com.vaadvivaad.lookup.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCaseRequest(

    @NotBlank(message = "CNR number is required")
    @Pattern(
        regexp = "^[A-Z]{4}[0-9]{2}[A-Z0-9]{9,15}$",
        message = "Invalid CNR format. Expected format: TNCH010012345678"
    )
    String cnrNumber,

    String caseType,
    String filingNumber,
    LocalDate filingDate,
    String registrationNumber,
    LocalDate registrationDate,
    String petitioner,
    String respondent,
    String courtName,
    String judgeName,

    @Valid
    List<CreateHearingRequest> hearings
) {
}