package com.vaadvivaad.notification.event;

import java.time.LocalDate;
import java.util.UUID;

public record HearingReminderEvent(
        UUID hearingId,
        UUID caseId,
        String cnrNumber,
        String caseDisplay,
        LocalDate hearingDate,
        String hearingPurpose,
        UUID userId,
        String userEmail,
        String userFullName,
        String userPhone
) {}