package com.example.attendance.auth;

import java.util.UUID;

public record WorkerPrincipal(UUID workerId, String workerCode, String fullName, UUID sessionId) {
}
