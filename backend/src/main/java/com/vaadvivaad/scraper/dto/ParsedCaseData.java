// scraper/dto/ParsedCaseData.java
package com.vaadvivaad.scraper.dto;

import java.time.LocalDate;
import java.util.List;

/*
 * WHY an internal ParsedCaseData record?
 *
 * The parser returns raw data — strings, possibly null, needs cleanup.
 * The service then validates and maps this to domain entities.
 * If the parser returned CourtCase entities directly:
 *   1. Parser would need to know about JPA entities (wrong layer)
 *   2. We couldn't test the parser without a JPA context
 *   3. Validation logic would be buried in the parser
 *
 * ParsedCaseData is a pure data bag — no JPA, no validation annotations,
 * just what the HTML said. The service decides what to do with it.
 *
 * INTERVIEW: "What is an anti-corruption layer?"
 * This IS an anti-corruption layer — it protects the domain model from
 * the messiness of the external data source (eCourts HTML).
 */
public record ParsedCaseData(
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
        List<ParsedHearing> hearings
) {
    public record ParsedHearing(
            LocalDate hearingDate,
            String purpose,
            LocalDate nextHearingDate,
            String notes
    ) {}
}