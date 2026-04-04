package com.vaadvivaad.subscription.service;

import com.vaadvivaad.config.RabbitMQConfig;
import com.vaadvivaad.lookup.entity.CourtCase;
import com.vaadvivaad.lookup.entity.Subscription;
import com.vaadvivaad.lookup.repository.CourtCaseRepository;
import com.vaadvivaad.lookup.repository.SubscriptionRepository;
import com.vaadvivaad.common.exception.ResourceNotFoundException;
import com.vaadvivaad.subscription.dto.SubscriptionRequest;
import com.vaadvivaad.subscription.dto.SubscriptionResponse;
import com.vaadvivaad.subscription.event.SubscriptionCreatedEvent;
import com.vaadvivaad.user.entity.User;
import com.vaadvivaad.user.repository.UserRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final CourtCaseRepository courtCaseRepository;
    private final UserRepository userRepository;
    private final RabbitTemplate rabbitTemplate;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            CourtCaseRepository courtCaseRepository,
            UserRepository userRepository,
            RabbitTemplate rabbitTemplate
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.courtCaseRepository = courtCaseRepository;
        this.userRepository = userRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public SubscriptionResponse subscribe(SubscriptionRequest request) {

        String email = SecurityContextHolder.getContext()
                .getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", "email", email));

        CourtCase courtCase = courtCaseRepository.findById(request.caseId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CourtCase", "id", request.caseId().toString()));

        if (subscriptionRepository.existsByUserIdAndCourtCaseId(
                user.getId(), courtCase.getId())) {
            throw new IllegalArgumentException(
                    "Already subscribed to case: " + courtCase.getCnrNumber());
        }

        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setCourtCase(courtCase);

        Subscription saved = subscriptionRepository.save(subscription);

        // Build display string from available fields
        String caseDisplay = buildCaseDisplay(courtCase);

        SubscriptionCreatedEvent event = new SubscriptionCreatedEvent(
                saved.getId(),
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                courtCase.getId(),
                courtCase.getCnrNumber(),
                caseDisplay,
                LocalDateTime.now()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                event
        );

        return toResponse(saved, courtCase);
    }

    @Transactional
    public void unsubscribe(UUID subscriptionId) {
        String email = SecurityContextHolder.getContext()
                .getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", "email", email));

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription", "id", subscriptionId.toString()));

        if (!subscription.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException(
                    "You can only unsubscribe from your own subscriptions");
        }

        subscriptionRepository.delete(subscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getMySubscriptions() {
        String email = SecurityContextHolder.getContext()
                .getAuthentication().getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", "email", email));

        return subscriptionRepository.findByUserId(user.getId())
                .stream()
                .map(sub -> toResponse(sub, sub.getCourtCase()))
                .toList();
    }

    // ─── Private Helpers ─────────────────────────────────────────────────

    private String buildCaseDisplay(CourtCase courtCase) {
        String petitioner = courtCase.getPetitioner() != null
                ? courtCase.getPetitioner() : "Unknown";
        String respondent = courtCase.getRespondent() != null
                ? courtCase.getRespondent() : "Unknown";
        return petitioner + " vs " + respondent;
    }

    private SubscriptionResponse toResponse(Subscription subscription,
                                             CourtCase courtCase) {
        return new SubscriptionResponse(
                subscription.getId(),
                courtCase.getId(),
                courtCase.getCnrNumber(),
                buildCaseDisplay(courtCase),
                subscription.getCreatedAt()
        );
    }
}