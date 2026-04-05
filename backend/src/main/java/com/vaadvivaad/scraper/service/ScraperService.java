// scraper/service/ScraperService.java
package com.vaadvivaad.scraper.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vaadvivaad.ai.event.SummaryRequestEvent;
import com.vaadvivaad.config.RabbitMQConfig;
import com.vaadvivaad.lookup.entity.CaseStatus;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.repository.CourtCaseRepository;
import com.vaadvivaad.lookup.repository.HearingRepository;
import com.vaadvivaad.scraper.client.ECourtWebClient;
import com.vaadvivaad.scraper.dto.ParsedCaseData;
import com.vaadvivaad.scraper.parser.ECourtHtmlParser;

@Service
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);

    /*
     * FRESHNESS THRESHOLD: 6 hours
     *
     * WHY 6 hours?
     * Court hearings are scheduled in advance — they don't change minute-to-minute.
     * But judges can reschedule on the morning of a hearing.
     * 6 hours balances:
     * - Not hammering eCourts with redundant requests
     * - Catching same-day reschedules if user checks in the morning
     *
     * This should be configurable via @Value in production.
     * For MVP, hard-coded is fine.
     */
    private static final int FRESHNESS_HOURS = 6;

    private final CourtCaseRepository courtCaseRepository;
    private final HearingRepository hearingRepository;
    private final ECourtWebClient eCourtWebClient;
    private final ECourtHtmlParser htmlParser;
    private final RabbitTemplate rabbitTemplate;

    public ScraperService(
            CourtCaseRepository courtCaseRepository,
            HearingRepository hearingRepository,
            ECourtWebClient eCourtWebClient,
            ECourtHtmlParser htmlParser,
            RabbitTemplate rabbitTemplate) {
        this.courtCaseRepository = courtCaseRepository;
        this.hearingRepository = hearingRepository;
        this.eCourtWebClient = eCourtWebClient;
        this.htmlParser = htmlParser;
        this.rabbitTemplate = rabbitTemplate;
    }

    /*
     * MAIN ENTRY POINT: scrapeOrRefresh
     *
     * This implements the "Cache-Aside with DB as primary" pattern:
     *
     * 1. Check DB (not Redis — DB is the source of truth for case data)
     *    Redis caches the QUERY result, not the scrape result.
     * 2. If fresh enough, return what we have
     * 3. If stale or missing, scrape eCourts
     * 4. Save to DB
     * 5. Evict Redis so next read gets fresh data
     *
     * WHY @CacheEvict here and not in CaseLookupService?
     * Because the scraper is responsible for freshness of case data.
     * The lookup service reads what's there. Clean responsibility split.
     *
     * WHY @Transactional?
     * We do multiple DB writes (save case + save each hearing).
     * If any hearing save fails, we want to roll back the whole scrape —
     * better to have no data than partial data for a case.
     */
    @Transactional
    @CacheEvict(cacheNames = "cases", allEntries = true)
    public CourtCase scrapeOrRefresh(String cnrNumber) {
        log.info("scrapeOrRefresh called for CNR: {}", cnrNumber);

        // Check if we already have fresh data
        Optional<CourtCase> existing = courtCaseRepository.findByCnrNumber(cnrNumber);

        if (existing.isPresent()) {
            CourtCase existingCase = existing.get();
            if (isFresh(existingCase)) {
                log.info("Case {} is fresh (scraped {}). Returning cached DB data.",
                        cnrNumber, existingCase.getLastScrapedAt());
                return existingCase;
            }
            log.info("Case {} is stale (scraped {}). Re-scraping.",
                    cnrNumber, existingCase.getLastScrapedAt());
        } else {
            log.info("Case {} not found in DB. Scraping for first time.", cnrNumber);
        }

        // Fetch from eCourts
        String html = eCourtWebClient.fetchCaseHtml(cnrNumber);

        // Parse HTML
        ParsedCaseData parsedData = htmlParser.parse(html);

        // Save to DB (upsert pattern)
        CourtCase savedCase = upsertCase(parsedData, existing);

        log.info("Successfully scraped and saved case {}", cnrNumber);
        return savedCase;
    }

    /*
     * FRESHNESS CHECK
     *
     * null lastScrapedAt means we have the case in DB but never scraped
     * it (it was manually inserted via seed data or the create endpoint).
     * Always treat null as stale.
     */
    private boolean isFresh(CourtCase courtCase) {
        if (courtCase.getLastScrapedAt() == null) return false;
        return courtCase.getLastScrapedAt()
                .isAfter(LocalDateTime.now().minusHours(FRESHNESS_HOURS));
    }

    /*
     * UPSERT PATTERN
     *
     * WHY not just save() directly?
     *
     * If we always create new entities, we violate the UNIQUE constraint
     * on cnr_number (V2 migration). We'd get a DB error on re-scrape.
     *
     * If we use Optional.orElse(new CourtCase()), we reuse the existing
     * entity's ID and JPA does an UPDATE instead of INSERT.
     *
     * For hearings: we DELETE existing hearings for this case and INSERT
     * fresh ones. Why? Because hearings can be:
     * - Rescheduled (same purpose, different date)
     * - Cancelled
     * - New ones added
     * Trying to merge by date is fragile. Full replace is simpler and correct.
     *
     * IMPORTANT: This delete-and-reinsert approach has implications for
     * NotificationLog (which references hearing_id). In production you'd
     * want to be more careful. For MVP, this is acceptable.
     */
    private CourtCase upsertCase(ParsedCaseData data, Optional<CourtCase> existing) {
        CourtCase courtCase = existing.orElse(new CourtCase());

        courtCase.setCnrNumber(data.cnrNumber());
        courtCase.setCaseType(data.caseType());
        courtCase.setFilingNumber(data.filingNumber());
        courtCase.setFilingDate(data.filingDate());
        courtCase.setRegistrationNumber(data.registrationNumber());
        courtCase.setRegistrationDate(data.registrationDate());
        courtCase.setStatus(mapStatus(data.status()));
        courtCase.setPetitioner(data.petitioner());
        courtCase.setRespondent(data.respondent());
        courtCase.setCourtName(data.courtName());
        courtCase.setJudgeName(data.judgeName());
        courtCase.setLastScrapedAt(LocalDateTime.now());

        CourtCase saved = courtCaseRepository.save(courtCase);

        // Delete existing hearings (full replace strategy)
        hearingRepository.deleteAllByCaseId(saved.getId());

        // Insert fresh hearings
        for (ParsedCaseData.ParsedHearing ph : data.hearings()) {
            if (ph.hearingDate() == null) {
                log.warn("Skipping hearing with null date for case {}",
                        data.cnrNumber());
                continue;
            }
            Hearing hearing = new Hearing();
            hearing.setCourtCase(saved);
            hearing.setHearingDate(ph.hearingDate());
            hearing.setPurpose(ph.purpose());
            hearing.setNotes(ph.notes());
            hearing.setNextHearingDate(ph.nextHearingDate());
            Hearing savedHearing = hearingRepository.save(hearing);

            /*
             * Publish summary request AFTER hearing is saved and has an ID.
             * WHY after save? Because the consumer fetches the hearing by ID.
             * Publishing before save means the consumer might fetch before
             * the transaction commits — race condition.
             *
             * WHY inside @Transactional?
             * If RabbitMQ publish fails, the transaction rolls back and
             * we don't have orphaned hearings with no summary request.
             * If the transaction rolls back after publish — the consumer
             * will try to fetch a non-existent hearing ID and log a warning.
             * That is acceptable for MVP. Production would use transactional
             * outbox pattern to guarantee exactly-once delivery.
             *
             * INTERVIEW: "How do you ensure consistency between DB writes
             * and message queue publishes?"
             * "For MVP, publish inside the transaction and accept the small
             * risk of a phantom message on rollback. For production, use the
             * transactional outbox pattern."
             */
            SummaryRequestEvent event = new SummaryRequestEvent(
                    savedHearing.getId(),
                    data.cnrNumber()
            );

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SUMMARY_EXCHANGE,
                    RabbitMQConfig.SUMMARY_ROUTING_KEY,
                    event
            );

            log.debug("Summary request published for hearing: {}",
                    savedHearing.getId());
        }

        return saved;
    }

    private CaseStatus mapStatus(String rawStatus) {
        if (rawStatus == null) return CaseStatus.PENDING;
        return switch (rawStatus.toUpperCase().trim()) {
            case "DISPOSED", "DISPOSED OF" -> CaseStatus.DISPOSED;
            case "TRANSFERRED" -> CaseStatus.TRANSFERRED;
            default -> CaseStatus.PENDING;
        };
    }
}