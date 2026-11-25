package com.example.attendance.auth;

import java.util.UUID;

public record AdminPrincipal(UUID adminUserId, String username, String displayName, AdminRole role, UUID sessionId) {
}
