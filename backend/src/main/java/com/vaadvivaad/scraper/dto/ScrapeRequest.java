// scraper/dto/ScrapeRequest.java
package com.vaadvivaad.scraper.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * WHY a separate ScrapeRequest instead of reusing CnrLookupRequest?
 *
 * CnrLookupRequest is in the lookup/ module. Having scraper/ depend on
 * lookup/ creates a module dependency that goes the wrong direction —
 * scraper is a data-gathering concern, lookup is a query concern.
 *
 * In a modular monolith, modules should depend on common/ not on each other.
 * Separate DTOs per module boundary is the correct approach even if
 * they look similar.
 */
public record ScrapeRequest(
        @NotBlank(message = "CNR number is required")
        @Pattern(
            regexp = "^[A-Z]{4}\\d{12}$",
            message = "CNR number must be 4 uppercase letters followed by 12 digits"
        )
        String cnrNumber
) {}