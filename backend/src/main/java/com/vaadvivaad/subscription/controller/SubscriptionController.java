package com.vaadvivaad.subscription.controller;

import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.subscription.dto.SubscriptionRequest;
import com.vaadvivaad.subscription.dto.SubscriptionResponse;
import com.vaadvivaad.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(
            @Valid @RequestBody SubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.subscribe(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @PathVariable UUID subscriptionId) {
        subscriptionService.unsubscribe(subscriptionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubscriptionResponse>>> getMySubscriptions() {
        List<SubscriptionResponse> subscriptions =
                subscriptionService.getMySubscriptions();
        return ResponseEntity.ok(ApiResponse.success(subscriptions));
    }
}