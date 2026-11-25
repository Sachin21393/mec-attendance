package com.example.attendance.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        String correlationId,
        Instant timestamp,
        Map<String, String> fieldErrors
) {
    public ApiError(String code, String message, String correlationId) {
        this(code, message, correlationId, Instant.now(), Map.of());
    }

    public ApiError(String code, String message, String correlationId, Map<String, String> fieldErrors) {
        this(code, message, correlationId, Instant.now(), fieldErrors == null ? Map.of() : fieldErrors);
    }
}
