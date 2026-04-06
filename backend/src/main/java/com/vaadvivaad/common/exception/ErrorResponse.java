package com.vaadvivaad.common.exception;

import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
    int status,
    String error,
    String message,
    Map<String, String> fieldErrors,
    LocalDateTime timestamp
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, null, LocalDateTime.now());
    }

    public static ErrorResponse withFieldErrors(int status, String error, String message,
                                                 Map<String, String> fieldErrors) {
        return new ErrorResponse(status, error, message, fieldErrors, LocalDateTime.now());
    }
}