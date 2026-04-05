// scraper/parser/ECourtHtmlParser.java
package com.vaadvivaad.scraper.parser;

import com.vaadvivaad.scraper.dto.ParsedCaseData;
import com.vaadvivaad.scraper.exception.ScraperException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ECourtHtmlParser {

    private static final Logger log = LoggerFactory.getLogger(ECourtHtmlParser.class);

    /*
     * eCourts uses dd-MM-YYYY format, NOT dd/MM/YYYY.
     * This is a silent killer — DateTimeParseException with no clear message
     * about why 24/01/2024 fails to parse as "dd-MM-yyyy".
     * Always log the raw date string when parsing fails.
     */
    private static final DateTimeFormatter ECOURT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /*
     * DESIGN DECISION: parse(String html) not parse(Document doc)
     *
     * Why accept a String? Because:
     * 1. In tests, we pass saved HTML file content as String
     * 2. WebClient gives us a String
     * 3. Parser is completely decoupled from how HTML was obtained
     *
     * If we accepted Document, tests would need to create Document objects,
     * coupling tests to Jsoup internals.
     */
    public ParsedCaseData parse(String html) {
        if (html == null || html.isBlank()) {
            throw new ScraperException("Empty HTML received — cannot parse case data");
        }

        Document doc = Jsoup.parse(html);

        /*
         * DEFENSIVE PARSING STRATEGY:
         *
         * eCourts is a government portal. The HTML can be:
         * - Missing entire sections for some case types
         * - Using slightly different class names between district courts
         * - Partially rendered if the server had an error
         *
         * Strategy: parse each section independently, log warnings for
         * missing sections, but don't fail the entire scrape because
         * one section is missing.
         *
         * The only hard failure: if the core case details table is missing,
         * we truly have nothing useful — that's a ScraperException.
         */

        // Step 1: Verify the page actually has case data
        // (vs. a "case not found" or CAPTCHA page)
        if (!isValidCasePage(doc)) {
            throw new ScraperException(
                "Page does not contain valid case data. " +
                "Possible causes: invalid CNR, CAPTCHA page, or session expired."
            );
        }

        // Step 2: Parse core case details
        String cnrNumber = parseCnrNumber(doc);
        String caseType = parseCaseType(doc);
        String filingNumber = parseFilingNumber(doc);
        LocalDate filingDate = parseFilingDate(doc);
        String registrationNumber = parseRegistrationNumber(doc);
        LocalDate registrationDate = parseRegistrationDate(doc);
        String status = parseCaseStatus(doc);

        // Step 3: Parse parties
        String petitioner = parsePetitioner(doc);
        String respondent = parseRespondent(doc);

        // Step 4: Parse court info
        String courtName = parseCourtName(doc);
        String judgeName = parseJudgeName(doc);

        // Step 5: Parse hearing history
        List<ParsedCaseData.ParsedHearing> hearings = parseHearings(doc);

        log.info("Parsed case {} — {} hearings found, status: {}",
                cnrNumber, hearings.size(), status);

        return new ParsedCaseData(
                cnrNumber, caseType, filingNumber, filingDate,
                registrationNumber, registrationDate, status,
                petitioner, respondent, courtName, judgeName, hearings
        );
    }

    /*
     * WHY this check first?
     *
     * When eCourts returns a CAPTCHA page or "no records found", the HTML
     * looks completely different — there's no caseBusinessDiv.
     * Without this guard, every subsequent parse method would return null
     * and we'd get a confusing NullPointerException deep in the code.
     *
     * Fail fast at the right layer with a meaningful message.
     */
    private boolean isValidCasePage(Document doc) {
        Element caseDiv = doc.getElementById("caseBusinessDiv");
        if (caseDiv == null) {
            log.warn("caseBusinessDiv not found in HTML. Page may be a CAPTCHA or error page. " +
                     "Page title: {}", doc.title());
            return false;
        }
        return true;
    }

    private String parseCnrNumber(Document doc) {
        /*
         * eCourts HTML pattern for CNR:
         * <table class="case_details">
         *   <tr><td>CNR Number</td><td>TNMD030056782023</td></tr>
         * </table>
         *
         * We find the row containing "CNR Number" text and take the next cell.
         * This is more robust than positional parsing (tr:first-child td:nth-child(2))
         * because eCourts sometimes adds/removes rows between versions.
         */
        return extractTableValue(doc, "case_details", "CNR Number");
    }

    private String parseCaseType(Document doc) {
        return extractTableValue(doc, "case_details", "Case Type");
    }

    private String parseFilingNumber(Document doc) {
        return extractTableValue(doc, "case_details", "Filing Number");
    }

    private LocalDate parseFilingDate(Document doc) {
        String rawDate = extractTableValue(doc, "case_details", "Filing Date");
        return parseDate(rawDate, "Filing Date");
    }

    private String parseRegistrationNumber(Document doc) {
        return extractTableValue(doc, "case_details", "Registration Number");
    }

    private LocalDate parseRegistrationDate(Document doc) {
        String rawDate = extractTableValue(doc, "case_details", "Registration Date");
        return parseDate(rawDate, "Registration Date");
    }

    private String parseCaseStatus(Document doc) {
        /*
         * Status is in a <span>, not a table — different pattern.
         * <span id="case_status_petitioner">Case Status : PENDING</span>
         *
         * We need to strip "Case Status : " prefix.
         */
        Element statusSpan = doc.getElementById("case_status_petitioner");
        if (statusSpan == null) {
            log.warn("Case status span not found — defaulting to PENDING");
            return "PENDING";
        }
        String raw = statusSpan.text().trim();
        // Strip prefix like "Case Status : " if present
        if (raw.contains(":")) {
            return raw.substring(raw.lastIndexOf(":") + 1).trim().toUpperCase();
        }
        return raw.toUpperCase();
    }

    private String parsePetitioner(Document doc) {
        /*
         * The petitioner table has one row per petitioner (multi-party cases).
         * We take the first petitioner's name from the first column.
         * Complex cases might have 5+ petitioners — we store the primary one.
         *
         * Future enhancement: store all petitioners in a separate table.
         * For MVP, primary petitioner is sufficient.
         */
        Element table = doc.selectFirst("table.Petitioner_Advocate_table");
        if (table == null) {
            log.warn("Petitioner table not found");
            return "Unknown";
        }
        // Skip header row, get first data row, first cell
        Elements rows = table.select("tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (!cells.isEmpty()) {
                String text = cells.get(0).text().trim();
                if (!text.isBlank() && !text.equalsIgnoreCase("Petitioner")) {
                    return text;
                }
            }
        }
        return "Unknown";
    }

    private String parseRespondent(Document doc) {
        Element table = doc.selectFirst("table.Respondent_Advocate_table");
        if (table == null) {
            log.warn("Respondent table not found");
            return "Unknown";
        }
        Elements rows = table.select("tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (!cells.isEmpty()) {
                String text = cells.get(0).text().trim();
                if (!text.isBlank() && !text.equalsIgnoreCase("Respondent")) {
                    return text;
                }
            }
        }
        return "Unknown";
    }

    private String parseCourtName(Document doc) {
        /*
         * Court name is typically in the page header or a dedicated div.
         * <div id="court_name">Principal District Court, Chennai</div>
         */
        Element courtDiv = doc.getElementById("court_name");
        if (courtDiv != null) {
            return courtDiv.text().trim();
        }
        // Fallback: look in case_details table
        String fromTable = extractTableValue(doc, "case_details", "Court Name");
        return fromTable != null ? fromTable : "Unknown Court";
    }

    private String parseJudgeName(Document doc) {
        return extractTableValue(doc, "case_details", "Judge");
    }

    private List<ParsedCaseData.ParsedHearing> parseHearings(Document doc) {
        List<ParsedCaseData.ParsedHearing> hearings = new ArrayList<>();

        Element historyTable = doc.selectFirst("table.history_table");
        if (historyTable == null) {
            log.warn("History table not found — no hearings parsed");
            return hearings;
        }

        Elements rows = historyTable.select("tr");

        /*
         * history_table structure:
         * Row 0: Header — "Hearing Date | Purpose | Next Hearing Date | Notes"
         * Row 1+: Data rows
         *
         * We skip rows where the first cell looks like a header
         * (contains "Hearing Date" text) to handle both single and
         * multi-row headers gracefully.
         */
        for (Element row : rows) {
            Elements cells = row.select("td");

            // Need at least 3 columns: date, purpose, next date
            if (cells.size() < 3) continue;

            String firstCellText = cells.get(0).text().trim();

            // Skip header rows
            if (firstCellText.equalsIgnoreCase("Hearing Date") ||
                firstCellText.isBlank()) {
                continue;
            }

            LocalDate hearingDate = parseDate(firstCellText, "Hearing Date in history");
            if (hearingDate == null) {
                // If we can't parse the date, this isn't a valid data row
                log.debug("Skipping row — couldn't parse hearing date from: '{}'",
                        firstCellText);
                continue;
            }

            String purpose = cells.get(1).text().trim();

            LocalDate nextHearingDate = null;
            if (cells.size() > 2) {
                nextHearingDate = parseDate(cells.get(2).text().trim(),
                        "Next Hearing Date");
            }

            String notes = "";
            if (cells.size() > 3) {
                notes = cells.get(3).text().trim();
            }

            hearings.add(new ParsedCaseData.ParsedHearing(
                    hearingDate, purpose, nextHearingDate, notes
            ));
        }

        log.debug("Parsed {} hearing records from history table", hearings.size());
        return hearings;
    }

    /*
     * SHARED UTILITY: Extract value from a key-value table
     *
     * This handles the extremely common eCourts pattern:
     * <table class="tableName">
     *   <tr><td>Key Label</td><td>Value</td></tr>
     * </table>
     *
     * WHY not use CSS attribute selectors like td:contains()?
     * Because td:contains() does substring matching — "Filing Number"
     * would also match "Filing Number (Old)". Exact text match is safer.
     */
    private String extractTableValue(Document doc, String tableClass, String keyLabel) {
        Element table = doc.selectFirst("table." + tableClass);
        if (table == null) {
            log.debug("Table with class '{}' not found", tableClass);
            return null;
        }

        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() >= 2) {
                String cellText = cells.get(0).text().trim();
                if (cellText.equalsIgnoreCase(keyLabel)) {
                    return cells.get(1).text().trim();
                }
            }
        }

        log.debug("Key '{}' not found in table '{}'", keyLabel, tableClass);
        return null;
    }

    /*
     * WHY a separate parseDate method?
     *
     * Three reasons:
     * 1. DRY — called from 4+ places
     * 2. Consistent null handling — returns null on failure, never throws
     * 3. Logging — always logs what it tried to parse, critical for debugging
     *
     * NEVER throw from a date parse in a scraper. Return null and let
     * the caller decide if null is acceptable (registration date might be
     * missing; hearing date cannot be).
     */
    private LocalDate parseDate(String rawDate, String fieldName) {
        if (rawDate == null || rawDate.isBlank() || rawDate.equalsIgnoreCase("N/A")) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim(), ECOURT_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse date for field '{}': '{}' — expected dd-MM-yyyy format",
                    fieldName, rawDate);
            return null;
        }
    }
}