// scraper/controller/ScraperController.java
package com.vaadvivaad.scraper.controller;

import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.lookup.dto.CaseResponse;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.scraper.dto.ScrapeRequest;
import com.vaadvivaad.scraper.service.ScraperService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cases")
public class ScraperController {

    private final ScraperService scraperService;

    /*
     * WHY inject ScraperService here and not CaseLookupService?
     * The scraper controller is specifically about fetching from eCourts.
     * CaseLookupController handles reading from our DB.
     * Different concerns, different controllers.
     */
    public ScraperController(ScraperService scraperService) {
        this.scraperService = scraperService;
    }

    /*
     * POST /api/cases/scrape
     *
     * Authenticated endpoint — why?
     * 1. Scraping has a real cost (CAPTCHA solving, bandwidth)
     * 2. We need to associate scraped cases with users for subscriptions
     * 3. Prevents abuse from anonymous traffic
     *
     * @PreAuthorize("isAuthenticated()") is slightly redundant here since
     * SecurityConfig already requires auth for /api/cases/**, but it's
     * explicit documentation of intent.
     */
    @PostMapping("/scrape")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaseResponse>> scrapeCase(
            @Valid @RequestBody ScrapeRequest request) {

        CourtCase courtCase = scraperService.scrapeOrRefresh(request.cnrNumber());

        // Reuse existing CaseResponse mapping from CaseLookupService
        CaseResponse response = mapToCaseResponse(courtCase);

        return ResponseEntity.ok(
                ApiResponse.success(response, "Case scraped successfully"));
    }

    /*
     * WHY mapping here instead of in ScraperService?
     *
     * ScraperService returns domain entities (CourtCase).
     * Controllers are responsible for DTO mapping.
     * ScraperService should not know about CaseResponse — that's an
     * API concern. This keeps the service layer pure.
     *
     * In a larger app, this mapping would move to a dedicated Mapper class.
     * For our size, controller mapping is acceptable.
     */
    private CaseResponse mapToCaseResponse(CourtCase courtCase) {
        // Reuse the same mapping logic already established in CaseLookupService
        // This is where a shared CaseMapper component would shine —
        // a refactor worth noting in your architecture discussion.
        return new CaseResponse(
                courtCase.getId(),
                courtCase.getCnrNumber(),
                courtCase.getCaseType(),
                courtCase.getFilingNumber(),
                courtCase.getFilingDate(),
                courtCase.getRegistrationNumber(),
                courtCase.getRegistrationDate(),
                courtCase.getStatus() != null ? courtCase.getStatus().name() : null,
                courtCase.getPetitioner(),
                courtCase.getRespondent(),
                courtCase.getCourtName(),
                courtCase.getJudgeName(),
                courtCase.getLastScrapedAt(),
                null // hearings not included in scrape response for brevity
        );
    }
}