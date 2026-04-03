package com.vaadvivaad.lookup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CnrLookupRequest(

    @NotBlank(message = "CNR number is required")
    @Pattern(
        regexp = "^[A-Z]{4}[0-9]{2}[A-Z0-9]{9,15}$",
        message = "Invalid CNR format. Expected format: TNCH010012345678"
    )
    String cnrNumber
) {
}