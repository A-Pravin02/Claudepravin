package com.aea.rbac;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A user as stored, including the credential hash. Never leaves this package. */
public record UserAccount(
        UUID id,
        UUID tenantId,
        String email,
        String passwordHash,
        String displayName,
        String status,
        int failedLogins,
        Instant lastLoginAt,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
