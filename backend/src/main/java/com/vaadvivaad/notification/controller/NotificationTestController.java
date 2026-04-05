package com.vaadvivaad.notification.controller;

import com.vaadvivaad.common.dto.ApiResponse;
import com.vaadvivaad.lookup.service.CaseLookupService;
import com.vaadvivaad.notification.scheduler.NotificationScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/test")
public class NotificationTestController {
	
    private static final Logger log = LoggerFactory.getLogger(CaseLookupService.class);

    private final NotificationScheduler notificationScheduler;

    public NotificationTestController(NotificationScheduler notificationScheduler) {
        this.notificationScheduler = notificationScheduler;
    }

    @PostMapping("/trigger-reminders")
    public ResponseEntity<ApiResponse<String>> triggerReminders(
            @RequestParam(required = false) String date) {
        
        LocalDate targetDate = (date != null)
                ? LocalDate.parse(date)
                : LocalDate.now().plusDays(1);

        notificationScheduler.sendRemindersForDate(targetDate);

        return ResponseEntity.ok(ApiResponse.success(
                "Reminder job triggered for date: " + targetDate));
    }
}