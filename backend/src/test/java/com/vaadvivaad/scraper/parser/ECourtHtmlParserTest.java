// src/test/java/com/vaadvivaad/scraper/parser/ECourtHtmlParserTest.java
package com.vaadvivaad.scraper.parser;

import com.vaadvivaad.scraper.dto.ParsedCaseData;
import com.vaadvivaad.scraper.exception.ScraperException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/*
 * WHY no @SpringBootTest here?
 *
 * ECourtHtmlParser has ZERO Spring dependencies — it's a pure Java class
 * that uses Jsoup. Starting the full Spring context for this test would
 * take 10-20 seconds for no benefit.
 *
 * Create the instance with `new` — fastest possible test.
 * This is a unit test in the truest sense.
 *
 * INTERVIEW: "What's the difference between @SpringBootTest and a pure unit test?"
 * "When would you use each?"
 * Answer: @SpringBootTest for integration tests (wiring, security, DB).
 * Pure unit tests for business logic — instant feedback, no infra needed.
 */
class ECourtHtmlParserTest {

    private ECourtHtmlParser parser;
    private String sampleHtml;

    @BeforeEach
    void setUp() throws IOException {
        parser = new ECourtHtmlParser();
        sampleHtml = Files.readString(
            Path.of("src/test/resources/fixtures/ecourts_sample_case.html")
        );
    }

    @Test
    void parse_withValidHtml_returnsParsedCaseData() {
        ParsedCaseData result = parser.parse(sampleHtml);

        assertThat(result.cnrNumber()).isEqualTo("TNMD030056782023");
        assertThat(result.caseType()).isEqualTo("Civil Suit");
        assertThat(result.filingNumber()).isEqualTo("CS/1234/2023");
        assertThat(result.filingDate()).isEqualTo(LocalDate.of(2023, 3, 15));
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.petitioner()).isEqualTo("Ravi Kumar");
        assertThat(result.respondent()).isEqualTo("State of Tamil Nadu");
        assertThat(result.courtName()).isEqualTo("Principal District Court, Chennai");
        assertThat(result.judgeName()).isEqualTo("Hon. Justice Krishnamurthy");
    }

    @Test
    void parse_withValidHtml_parsesAllHearings() {
        ParsedCaseData result = parser.parse(sampleHtml);

        assertThat(result.hearings()).hasSize(3);

        ParsedCaseData.ParsedHearing firstHearing = result.hearings().get(0);
        assertThat(firstHearing.hearingDate()).isEqualTo(LocalDate.of(2023, 5, 10));
        assertThat(firstHearing.purpose()).isEqualTo("Admission");
        assertThat(firstHearing.nextHearingDate()).isEqualTo(LocalDate.of(2023, 6, 15));
        assertThat(firstHearing.notes()).isEqualTo("Summons issued");
    }

    @Test
    void parse_withNullNextDate_parsesGracefully() {
        ParsedCaseData result = parser.parse(sampleHtml);

        // Third hearing has "N/A" next date — should parse as null
        ParsedCaseData.ParsedHearing lastHearing = result.hearings().get(2);
        assertThat(lastHearing.nextHearingDate()).isNull();
    }

    @Test
    void parse_withEmptyHtml_throwsScraperException() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(ScraperException.class)
                .hasMessageContaining("Empty HTML");
    }

    @Test
    void parse_withNoCaseDiv_throwsScraperException() {
        String htmlWithoutCaseDiv = "<html><body><p>No case here</p></body></html>";

        assertThatThrownBy(() -> parser.parse(htmlWithoutCaseDiv))
                .isInstanceOf(ScraperException.class)
                .hasMessageContaining("caseBusinessDiv");
    }
}