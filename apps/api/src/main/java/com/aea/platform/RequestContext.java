package com.aea.platform;

import java.util.UUID;

/**
 * Correlates everything emitted while handling one request: the audit row, the
 * log lines, and the X-Request-Id header returned to the caller. An operator
 * investigating a denial should be able to go from the header a user quotes to
 * the exact audit entry.
 */
public final class RequestContext {

    private static final ThreadLocal<UUID> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(UUID requestId) {
        REQUEST_ID.set(requestId);
    }

    public static UUID requestId() {
        UUID id = REQUEST_ID.get();
        return id != null ? id : UUID.randomUUID();
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
