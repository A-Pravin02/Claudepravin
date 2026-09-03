package com.aea.platform;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, derived entirely from a verified token.
 *
 * SECURITY: tenantId comes from the token and nowhere else. A tenant id in a
 * request body or query parameter is ignored and logged as a security event --
 * accepting one would let any authenticated user address another tenant's data
 * before the database ever sees the query.
 */
public record Principal(
        UUID userId,
        UUID tenantId,
        String email,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
