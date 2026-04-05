// ai/service/SummaryService.java

package com.vaadvivaad.ai.service;

import com.vaadvivaad.ai.client.ClaudeClient;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.repository.HearingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final ClaudeClient claudeClient;
    private final HearingRepository hearingRepository;

    public SummaryService(ClaudeClient claudeClient,
                          HearingRepository hearingRepository) {
        this.claudeClient = claudeClient;
        this.hearingRepository = hearingRepository;
    }

    @Transactional
    public void generateAndSave(UUID hearingId) {
    	
        log.info("Claude API not configured — skipping summary for hearing: {}", hearingId);
        return;
        
//        log.info("Generating Hindi summary for hearing: {}", hearingId);
//
//       Hearing hearing = hearingRepository.findById(hearingId)
//               .orElseThrow(() -> {
//                   log.warn("Hearing not found for summary generation: {}", hearingId);
//                   return new RuntimeException("Hearing not found: " + hearingId);
//               });
//
//       // Skip if summary already exists
//       if (hearing.getAiSummaryHindi() != null
//               && !hearing.getAiSummaryHindi().isBlank()) {
//           log.info("Summary already exists for hearing: {} — skipping", hearingId);
//           return;
//       }
//
//       String prompt = buildPrompt(hearing);
//       String summary = claudeClient.complete(prompt);
//
//       if (summary == null || summary.isBlank()) {
//           log.warn("Empty summary received for hearing: {} — not saving", hearingId);
//           return;
//       }
//
//       hearing.setAiSummaryHindi(summary.trim());
//       hearingRepository.save(hearing);
//
//       log.info("Hindi summary saved for hearing: {}", hearingId);
    }

    /*
     * PROMPT ENGINEERING DECISIONS:
     *
     * 1. Role assignment — "You are a legal assistant..."
     *    Claude performs better with explicit role context.
     *    It calibrates vocabulary and tone to the role.
     *
     * 2. Audience specification — "explain to a common Indian litigant"
     *    Without this, Claude gives lawyer-level explanations.
     *    The user is a litigant, not a lawyer.
     *
     * 3. Language instruction — "Respond ONLY in Hindi"
     *    Without "ONLY", Claude sometimes responds in English
     *    or gives a bilingual response.
     *
     * 4. Length constraint — "2-3 sentences"
     *    Matches our max_tokens: 300 budget.
     *    Also prevents Claude from writing an essay.
     *
     * 5. Specific output rule — "Do not use legal jargon"
     *    Critical for this use case. "I.A. Disposal" must become
     *    plain language, not "Interlocutory Application adjudication."
     *
     * 6. Actionable ending — "Tell them what to do or expect"
     *    Users need to know: do I need to come? bring documents?
     *    A summary without actionable guidance is incomplete.
     *
     * WHY include notes/detail in the prompt?
     *    Court clerk notes often contain the actual substance:
     *    "Defendant absent, ex-parte order passed"
     *    That context changes the Hindi summary significantly.
     */
    private String buildPrompt(Hearing hearing) {
        String courtCase = hearing.getCourtCase() != null
                ? hearing.getCourtCase().getCnrNumber()
                : "Unknown";

        String caseParties = hearing.getCourtCase() != null
                ? hearing.getCourtCase().getPetitioner()
                  + " vs "
                  + hearing.getCourtCase().getRespondent()
                : "";

        String notes = (hearing.getNotes() != null
                && !hearing.getNotes().isBlank())
                ? hearing.getNotes()
                : "No additional notes";

        String nextDate = hearing.getNextHearingDate() != null
                ? hearing.getNextHearingDate().toString()
                : "Not fixed yet";

        return """
                You are a legal assistant helping common Indian litigants understand
                their court hearings. Explain the following hearing details in simple,
                plain Hindi (not legal Hindi). The person reading this is not a lawyer.

                Case: %s (%s)
                Hearing Date: %s
                Purpose of Hearing: %s
                Court Notes: %s
                Next Hearing Date: %s

                Instructions:
                - Respond ONLY in Hindi using Devanagari script
                - Write exactly 2-3 sentences
                - Do NOT use legal jargon or English legal terms
                - Explain what happened at this hearing in plain language
                - Tell the person what to expect or do next
                - If next hearing date is given, mention it naturally

                Hindi summary:
                """.formatted(
                        courtCase,
                        caseParties,
                        hearing.getHearingDate(),
                        hearing.getPurpose() != null ? hearing.getPurpose() : "General Hearing",
                        notes,
                        nextDate
                );
    }
}