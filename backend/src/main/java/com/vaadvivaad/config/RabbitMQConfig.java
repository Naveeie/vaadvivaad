package com.vaadvivaad.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ─── Constants ───────────────────────────────────────────────────────
    public static final String EXCHANGE         = "vaadvivaad.exchange";
    public static final String NOTIFICATION_QUEUE = "notification.queue";
    public static final String DLQ              = "notification.dlq";
    public static final String ROUTING_KEY      = "subscription.created";
    public static final String DLQ_ROUTING_KEY  = "subscription.created.dlq";

    // ─── Dead Letter Queue ───────────────────────────────────────────────

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    // ─── Main Queue (points to DLQ on failure) ───────────────────────────

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    // ─── Exchange ────────────────────────────────────────────────────────

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(EXCHANGE);
    }

    // ─── Bindings ────────────────────────────────────────────────────────

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder
                .bind(notificationQueue())
                .to(exchange())
                .with(ROUTING_KEY);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(exchange())
                .with(DLQ_ROUTING_KEY);
    }

    // ─── JSON Message Converter ──────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}