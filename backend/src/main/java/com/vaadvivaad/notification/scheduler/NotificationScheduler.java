package com.vaadvivaad.notification.scheduler;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vaadvivaad.config.RabbitMQConfig;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.entity.Subscription;
import com.vaadvivaad.lookup.repository.HearingRepository;
import com.vaadvivaad.lookup.repository.SubscriptionRepository;
import com.vaadvivaad.notification.event.HearingReminderEvent;
import com.vaadvivaad.scraper.exception.ScraperException;
import com.vaadvivaad.scraper.service.ScraperService;

@Component
public class NotificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationScheduler.class);

    private final HearingRepository hearingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ScraperService scraperService;
    
    public NotificationScheduler(
            HearingRepository hearingRepository,
            SubscriptionRepository subscriptionRepository,
            RabbitTemplate rabbitTemplate,
            ScraperService scraperService
    ) {
        this.hearingRepository = hearingRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.scraperService = scraperService;
    }

    // Runs every day at 8:00 AM
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void sendHearingReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("Scheduler running — checking hearings for: {}", tomorrow);

        List<Hearing> tomorrowsHearings =
                hearingRepository.findByNextHearingDate(tomorrow);

        if (tomorrowsHearings.isEmpty()) {
            log.info("No hearings scheduled for tomorrow");
            return;
        }

        log.info("Found {} hearing(s) for tomorrow", tomorrowsHearings.size());

        for (Hearing hearing : tomorrowsHearings) {
            processHearing(hearing);
        }
    }

    // Trigger manually for testing — hits today's hearings
    public void sendRemindersForDate(LocalDate date) {
        log.info("Manual trigger — checking hearings for: {}", date);
        List<Hearing> hearings = hearingRepository.findByNextHearingDate(date);
        hearings.forEach(this::processHearing);
    }

    private void processHearing(Hearing hearing) {
        CourtCase courtCase = hearing.getCourtCase();

        List<Subscription> subscriptions =
                subscriptionRepository.findByCourtCaseId(courtCase.getId());

        if (subscriptions.isEmpty()) {
            log.debug("No subscribers for case: {}", courtCase.getCnrNumber());
            return;
        }

        log.info("Publishing {} reminder(s) for case: {}",
                subscriptions.size(), courtCase.getCnrNumber());

        for (Subscription subscription : subscriptions) {
            HearingReminderEvent event = new HearingReminderEvent(
                    hearing.getId(),
                    courtCase.getId(),
                    courtCase.getCnrNumber(),
                    buildCaseDisplay(courtCase),
                    hearing.getHearingDate(),
                    hearing.getPurpose(),
                    subscription.getUser().getId(),
                    subscription.getUser().getEmail(),
                    subscription.getUser().getFullName(),
                    subscription.getUser().getPhoneNumber()
            );

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.HEARING_REMINDER_ROUTING_KEY,
                    event
            );
        }
    }

    private String buildCaseDisplay(CourtCase courtCase) {
        String petitioner = courtCase.getPetitioner() != null
                ? courtCase.getPetitioner() : "Unknown";
        String respondent = courtCase.getRespondent() != null
                ? courtCase.getRespondent() : "Unknown";
        return petitioner + " vs " + respondent;
    }
    
    /*
     * DAILY RE-SCRAPE JOB
     *
     * Runs at 6 AM — before the 8 AM reminder job.
     * Why before? Because the reminder job reads hearing dates.
     * If we scrape at 6 AM, by 8 AM we have fresh data including
     * any rescheduled hearings.
     *
     * WHY not scrape and remind in the same job?
     * Single Responsibility — scraping can fail, reminders should still
     * send for data we already have. Separating them means a scrape failure
     * at 6 AM doesn't silence reminders at 8 AM.
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void reScrapeTrackedCases() {
        log.info("Starting daily re-scrape job");

        List<String> trackedCnrNumbers = subscriptionRepository.findAllDistinctCnrNumbers(); // we'll add this query

        int successCount = 0;
        int failCount = 0;

        for (String cnrNumber : trackedCnrNumbers) {
            try {
                scraperService.scrapeOrRefresh(cnrNumber);
                successCount++;
                // Small delay between requests — be polite to eCourts
                Thread.sleep(2000);
            } catch (ScraperException e) {
                log.warn("Re-scrape failed for CNR {}: {}", cnrNumber, e.getMessage());
                failCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Re-scrape job interrupted");
                break;
            }
        }

        log.info("Re-scrape job complete. Success: {}, Failed: {}",
                successCount, failCount);
    }
}