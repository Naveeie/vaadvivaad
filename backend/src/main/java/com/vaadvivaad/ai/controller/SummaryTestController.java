// ai/controller/SummaryTestController.java

package com.vaadvivaad.ai.controller;

import com.vaadvivaad.ai.service.SummaryService;
import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.repository.HearingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/test")
public class SummaryTestController {

    private final SummaryService summaryService;
    private final HearingRepository hearingRepository;

    public SummaryTestController(SummaryService summaryService,
                                  HearingRepository hearingRepository) {
        this.summaryService = summaryService;
        this.hearingRepository = hearingRepository;
    }

    /*
     * WHY a test endpoint instead of just triggering via scrape?
     *
     * The scraper requires eCourts connectivity (blocked in dev).
     * This endpoint lets us test the Claude integration independently:
     * 1. Pick any hearing UUID from seed data
     * 2. Call this endpoint
     * 3. Verify Hindi summary appears in DB
     *
     * Tests one thing at a time — good engineering practice.
     */
    @PostMapping("/generate-summary/{hearingId}")
    public ResponseEntity<ApiResponse<String>> generateSummary(
            @PathVariable UUID hearingId) {

        summaryService.generateAndSave(hearingId);

        // Fetch the updated hearing to return the summary
        Hearing hearing = hearingRepository.findById(hearingId)
                .orElseThrow();

        String summary = hearing.getAiSummaryHindi();

        return ResponseEntity.ok(
                ApiResponse.success(
                        summary != null ? summary : "Summary generation failed — check logs",
                        "Summary generation attempted"
                ));
    }
}