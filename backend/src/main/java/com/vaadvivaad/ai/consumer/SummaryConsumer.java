// ai/consumer/SummaryConsumer.java

package com.vaadvivaad.ai.consumer;

import com.vaadvivaad.ai.event.SummaryRequestEvent;
import com.vaadvivaad.ai.service.SummaryService;
import com.vaadvivaad.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SummaryConsumer {

    private static final Logger log = LoggerFactory.getLogger(SummaryConsumer.class);
    private final SummaryService summaryService;

    public SummaryConsumer(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /*
     * WHY @RabbitListener on a separate consumer class instead of
     * putting this in the existing NotificationConsumer?
     *
     * NotificationConsumer handles time-sensitive reminders.
     * SummaryConsumer handles AI calls that can take 2-5 seconds each.
     *
     * If they shared a consumer, a slow Claude response would block
     * reminder processing. Separate consumers process their queues
     * independently.
     *
     * INTERVIEW: "How do you prevent slow consumers from blocking fast ones?"
     * "Separate queues, separate consumers, separate thread pools."
     */
    @RabbitListener(queues = RabbitMQConfig.SUMMARY_REQUEST_QUEUE)
    public void handleSummaryRequest(SummaryRequestEvent event) {
        log.info("Summary request received — hearing: {}, CNR: {}",
                event.hearingId(), event.cnrNumber());

        try {
            summaryService.generateAndSave(event.hearingId());
        } catch (Exception e) {
            /*
             * WHY catch here and not let it propagate?
             *
             * If this method throws, RabbitMQ will:
             * 1. Nack the message
             * 2. Retry it (based on retry config)
             * 3. Eventually send to DLQ
             *
             * For a transient Claude outage, that retry behavior is correct.
             * But for a permanent failure (hearing deleted, invalid data),
             * we want to log and move on — not fill the DLQ with
             * unprocessable messages.
             *
             * Log the error, let the message be acknowledged (consumed),
             * and the DLQ stays clean for genuinely retryable failures.
             */
            log.error("Failed to generate summary for hearing {}: {}",
                    event.hearingId(), e.getMessage(), e);
        }
    }
}