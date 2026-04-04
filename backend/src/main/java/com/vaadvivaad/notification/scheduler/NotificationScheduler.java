package com.vaadvivaad.notification.scheduler;

import com.vaadvivaad.config.RabbitMQConfig;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Hearing;
import com.vaadvivaad.lookup.entity.Subscription;
import com.vaadvivaad.lookup.repository.HearingRepository;
import com.vaadvivaad.lookup.repository.SubscriptionRepository;
import com.vaadvivaad.notification.event.HearingReminderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
public class NotificationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationScheduler.class);

    private final HearingRepository hearingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RabbitTemplate rabbitTemplate;

    public NotificationScheduler(
            HearingRepository hearingRepository,
            SubscriptionRepository subscriptionRepository,
            RabbitTemplate rabbitTemplate
    ) {
        this.hearingRepository = hearingRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    // Runs every day at 8:00 AM
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void sendHearingReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("Scheduler running — checking hearings for: {}", tomorrow);

        List<Hearing> tomorrowsHearings =
                hearingRepository.findByHearingDate(tomorrow);

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
        List<Hearing> hearings = hearingRepository.findByHearingDate(date);
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
}