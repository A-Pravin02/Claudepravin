package com.aea.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Binds the current request's tenant to the thread so the transaction manager
 * can scope the database session to it.
 *
 * SECURITY: this is the input to PostgreSQL's row-level security. Leaving a
 * stale value bound would show one user another tenant's rows, so the filter
 * that sets it clears it in a finally block, and the value is transaction-local
 * in the database (see TenantAwareTransactionManager). Absent context is
 * fail-closed: RLS policies compare against NULL and no rows match.
 */
public final class TenantContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Optional<Principal> principal() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> tenantId() {
        return principal().map(Principal::tenantId);
    }

    /** Throws when a caller requires a principal; use principal() to test. */
    public static Principal require() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException("No authenticated principal bound to this thread");
        }
        return p;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
