package com.aea.auth;

import com.aea.platform.ApiException;
import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import com.aea.rbac.UserAccount;
import com.aea.rbac.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Login, refresh and logout.
 *
 * A structural rule runs through this class: <b>failure is returned, never
 * thrown, from inside a transaction.</b> Throwing marks the transaction
 * rollback-only, which would silently undo the security bookkeeping written
 * moments earlier -- the failed-login counter, and the revocation that
 * refresh-token theft triggers. Each method therefore computes an Optional
 * inside the transaction and throws after it has committed.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A BCrypt hash no password will match. Verified against when no user
     * exists, so an unknown email costs roughly the same time as a known one.
     * Without it, response timing enumerates valid users.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final TransactionTemplate tx;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       RefreshTokenService refreshTokens, TransactionTemplate tx) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.tx = tx;
    }

    public Tokens login(String email, String password) {
        // Step 1: resolve the tenant. No tenant context exists yet, so this
        // reads only the routing table (see V003).
        var route = tx.execute(status -> users.resolveLoginRoute(email));

        if (route == null || route.isEmpty()) {
            encoder.matches(password, DUMMY_HASH);
            throw ApiException.unauthorized();
        }

        // Step 2: bind the tenant. Everything below runs under RLS.
        return withTenant(route.get().tenantId(), () -> {
            Optional<Tokens> issued = tx.execute(status -> {
                UserAccount user = users.findById(route.get().userId()).orElse(null);
                if (user == null) {
                    return Optional.empty();
                }
                if (!user.isActive() || user.passwordHash() == null
                        || !encoder.matches(password, user.passwordHash())) {
                    users.recordFailedLogin(user.id());
                    log.info("Failed login for user {} in tenant {}", user.id(), user.tenantId());
                    return Optional.empty();
                }
                users.recordSuccessfulLogin(user.id());
                return Optional.of(issueFor(user));
            });
            return issued.orElseThrow(ApiException::unauthorized);
        });
    }

    public Tokens refresh(String refreshToken) {
        // refresh_tokens is tenant-scoped by RLS, but a refresh arrives with no
        // tenant context. Route first on the token's untrusted user prefix,
        // then verify the full hash inside that tenant's scope. A forged prefix
        // cannot help: the stored hash covers the whole token.
        UUID claimed = RefreshTokenService.claimedUser(refreshToken)
                .orElseThrow(ApiException::unauthorized);

        var route = tx.execute(status -> users.resolveLoginRouteById(claimed));
        if (route == null || route.isEmpty()) {
            throw ApiException.unauthorized();
        }

        return withTenant(route.get().tenantId(), () -> {
            Optional<Tokens> issued = tx.execute(status -> {
                // consume() revokes the whole chain when it detects reuse. That
                // write must survive, so nothing throws until this commits.
                UUID userId = refreshTokens.consume(refreshToken).orElse(null);
                if (userId == null) {
                    return Optional.empty();
                }
                UserAccount user = users.findById(userId).orElse(null);
                // Re-read rather than trusting the old token: this is where a
                // revoked role or a disabled account takes effect.
                if (user == null || !user.isActive()) {
                    return Optional.empty();
                }
                return Optional.of(issueFor(user));
            });
            return issued.orElseThrow(ApiException::unauthorized);
        });
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    /** Binds a tenant for the duration of the action and always unbinds it. */
    private <T> T withTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        TenantContext.set(new Principal(null, tenantId, null, Set.of(), Set.of()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private Tokens issueFor(UserAccount user) {
        Principal principal = new Principal(user.id(), user.tenantId(), user.email(),
                user.roles(), user.permissions());
        return new Tokens(
                jwt.issueAccessToken(principal),
                refreshTokens.issue(user.tenantId(), user.id()),
                jwt.accessTtlSeconds());
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}
}
