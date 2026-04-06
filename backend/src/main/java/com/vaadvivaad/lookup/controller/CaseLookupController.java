package com.vaadvivaad.lookup.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.lookup.dto.CaseResponse;
import com.vaadvivaad.lookup.dto.CnrLookupRequest;
import com.vaadvivaad.lookup.dto.CreateCaseRequest;
import com.vaadvivaad.lookup.service.CaseLookupService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/cases")
public class CaseLookupController {

    private final CaseLookupService caseLookupService;

    public CaseLookupController(CaseLookupService caseLookupService) {
        this.caseLookupService = caseLookupService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CaseResponse>> createCase(
            @Valid @RequestBody CreateCaseRequest request) {
        CaseResponse created = caseLookupService.createCase(request);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(created, "Case created successfully"));
    }

    @PostMapping("/lookup")
    public ResponseEntity<ApiResponse<CaseResponse>> lookupByCnr(
            @Valid @RequestBody CnrLookupRequest request) {
        CaseResponse caseResponse = caseLookupService.lookupByCnr(request.cnrNumber());
        return ResponseEntity.ok(ApiResponse.success(caseResponse, "Case found"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CaseResponse>> getById(@PathVariable UUID id) {
        CaseResponse caseResponse = caseLookupService.lookupById(id);
        return ResponseEntity.ok(ApiResponse.success(caseResponse, "Case found"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<CaseResponse>>> listCases(
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<CaseResponse> cases = caseLookupService.listCases(pageable);
        return ResponseEntity.ok(ApiResponse.success(cases, "Cases retrieved"));
    }
}