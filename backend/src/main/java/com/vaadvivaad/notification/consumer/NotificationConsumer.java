package com.vaadvivaad.notification.consumer;

import com.vaadvivaad.config.RabbitMQConfig;
import com.vaadvivaad.notification.event.HearingReminderEvent;
import com.vaadvivaad.subscription.event.SubscriptionCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
        log.info("=== SUBSCRIPTION EVENT RECEIVED ===");
        log.info("User  : {} ({})", event.userFullName(), event.userEmail());
        log.info("Case  : {} — {}", event.cnrNumber(), event.caseTitle());
        log.info("Sub ID: {}", event.subscriptionId());
        log.info("===================================");
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleHearingReminder(HearingReminderEvent event) {
        log.info("=== HEARING REMINDER EVENT RECEIVED ===");
        log.info("User    : {} ({})", event.userFullName(), event.userEmail());
        log.info("Case    : {} — {}", event.cnrNumber(), event.caseDisplay());
        log.info("Hearing : {} for {}", event.hearingDate(), event.hearingPurpose());
        log.info("Phone   : {}", event.userPhone());
        log.info("Action  : WhatsApp/SMS reminder (Twilio in Session 9)");
        log.info("=======================================");
    }
}