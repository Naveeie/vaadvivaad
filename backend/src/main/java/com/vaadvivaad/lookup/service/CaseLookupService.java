package com.vaadvivaad.lookup.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaadvivaad.common.exception.CnrValidationException;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import com.vaadvivaad.lookup.dto.CaseResponse;
import com.vaadvivaad.lookup.dto.CreateCaseRequest;
import com.vaadvivaad.lookup.dto.CreateHearingRequest;
import com.vaadvivaad.lookup.dto.HearingResponse;
import com.vaadvivaad.lookup.entity.CaseStatus;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.repository.CourtCaseRepository;
import com.vaadvivaad.lookup.repository.HearingRepository;

@Service
public class CaseLookupService {

    private static final Logger log = LoggerFactory.getLogger(CaseLookupService.class);

    private final CourtCaseRepository courtCaseRepository;
    private final HearingRepository hearingRepository;

    public CaseLookupService(CourtCaseRepository courtCaseRepository,
                             HearingRepository hearingRepository) {
        this.courtCaseRepository = courtCaseRepository;
        this.hearingRepository = hearingRepository;
    }

    // ==================== READ OPERATIONS ====================

    @Transactional(readOnly = true)
    public CaseResponse lookupByCnr(String cnrNumber) {
        log.info("Looking up case with CNR: {}", cnrNumber);

        CourtCase courtCase = courtCaseRepository.findByCnrNumber(cnrNumber)
            .orElseThrow(() -> new ResourceNotFoundException("Court case", "cnrNumber", cnrNumber));

        List<Hearing> hearings = hearingRepository
            .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());

        log.info("Found case {} with {} hearings", cnrNumber, hearings.size());

        return mapToResponse(courtCase, hearings);
    }

    @Transactional(readOnly = true)
    public CaseResponse lookupById(UUID id) {
        CourtCase courtCase = courtCaseRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Court case", "id", id));

        List<Hearing> hearings = hearingRepository
            .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());

        return mapToResponse(courtCase, hearings);
    }

    @Transactional(readOnly = true)
    public Page<CaseResponse> listCases(Pageable pageable) {
        log.debug("Listing cases with pageable: {}", pageable);

        Page<CourtCase> casePage = courtCaseRepository.findAll(pageable);

        return casePage.map(courtCase -> {
            List<Hearing> hearings = hearingRepository
                .findByCourtCaseIdOrderByHearingDateDesc(courtCase.getId());
            return mapToResponse(courtCase, hearings);
        });
    }

    // ==================== WRITE OPERATIONS ====================

    @Transactional
    public CaseResponse createCase(CreateCaseRequest request) {
        log.info("Creating case with CNR: {}", request.cnrNumber());

        // Check for duplicate CNR
        if (courtCaseRepository.existsByCnrNumber(request.cnrNumber())) {
            throw new CnrValidationException(
                "Case with CNR " + request.cnrNumber() + " already exists");
        }

        // Build the entity
        CourtCase courtCase = new CourtCase();
        courtCase.setCnrNumber(request.cnrNumber());
        courtCase.setCaseType(request.caseType());
        courtCase.setFilingNumber(request.filingNumber());
        courtCase.setFilingDate(request.filingDate());
        courtCase.setRegistrationNumber(request.registrationNumber());
        courtCase.setRegistrationDate(request.registrationDate());
        courtCase.setStatus(CaseStatus.PENDING);
        courtCase.setPetitioner(request.petitioner());
        courtCase.setRespondent(request.respondent());
        courtCase.setCourtName(request.courtName());
        courtCase.setJudgeName(request.judgeName());

        // Add hearings with back-reference (CRITICAL!)
        if (request.hearings() != null) {
            for (CreateHearingRequest hearingRequest : request.hearings()) {
                Hearing hearing = new Hearing();
                hearing.setHearingDate(hearingRequest.hearingDate());
                hearing.setPurpose(hearingRequest.purpose());
                hearing.setDetail(hearingRequest.detail());

                // Set the back-reference — parent ↔ child link
                hearing.setCourtCase(courtCase);
                courtCase.getHearings().add(hearing);
            }
        }

        // Save — cascade persists hearings too
        CourtCase saved = courtCaseRepository.save(courtCase);

        log.info("Created case {} with {} hearings",
            saved.getCnrNumber(), saved.getHearings().size());

        return mapToResponse(saved, saved.getHearings());
    }

    // ==================== MAPPING ====================

    private CaseResponse mapToResponse(CourtCase courtCase, List<Hearing> hearings) {
        List<HearingResponse> hearingResponses = hearings.stream()
            .map(this::mapHearingToResponse)
            .toList();

        return new CaseResponse(
            courtCase.getId(),
            courtCase.getCnrNumber(),
            courtCase.getCaseType(),
            courtCase.getFilingNumber(),
            courtCase.getFilingDate(),
            courtCase.getRegistrationNumber(),
            courtCase.getRegistrationDate(),
            courtCase.getStatus().name(),
            courtCase.getPetitioner(),
            courtCase.getRespondent(),
            courtCase.getCourtName(),
            courtCase.getJudgeName(),
            courtCase.getLastScrapedAt(),
            hearingResponses
        );
    }

    private HearingResponse mapHearingToResponse(Hearing hearing) {
        return new HearingResponse(
            hearing.getId(),
            hearing.getHearingDate(),
            hearing.getPurpose(),
            hearing.getDetail(),
            hearing.getAiSummaryHindi()
        );
    }
}