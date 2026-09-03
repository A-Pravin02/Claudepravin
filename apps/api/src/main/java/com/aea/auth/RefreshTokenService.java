package com.aea.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * SECURITY: only a SHA-256 hash of each token is stored, so a database read
 * cannot mint sessions. Rotation is enforced -- using a refresh token consumes
 * it and issues a replacement. Presenting an already-consumed token is treated
 * as theft (the legitimate holder and the attacker both have a copy), so the
 * whole chain for that user is revoked rather than just the one token.
 *
 * The token is shaped "<userId>.<random>". refresh_tokens is tenant-scoped by
 * RLS, but a refresh arrives with no tenant context -- the same ordering
 * problem login has. The prefix lets the caller be routed to a tenant before
 * the scoped lookup runs, reusing the user_directory route. The prefix is
 * untrusted and carries no authority: the stored hash covers the WHOLE token,
 * so editing the prefix only guarantees the hash will not match, and routing
 * to the wrong tenant just means the scoped lookup finds nothing.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final long ttlSeconds;

    public RefreshTokenService(NamedParameterJdbcTemplate jdbc,
                               @Value("${aea.auth.refresh-ttl-seconds:1209600}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(UUID tenantId, UUID userId) {
        String token = userId + "." + randomSecret();
        jdbc.update("""
                INSERT INTO refresh_tokens (tenant_id, user_id, token_hash, expires_at)
                VALUES (:tenant, :user, :hash, :expires)
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("user", userId)
                        .addValue("hash", sha256(token))
                        .addValue("expires", java.sql.Timestamp.from(
                                Instant.now().plusSeconds(ttlSeconds))));
        return token;
    }

    /**
     * The user a token claims to belong to, for routing only. Never treated as
     * proof of anything -- consume() still verifies the full hash under the
     * tenant scope this resolves.
     */
    public static Optional<UUID> claimedUser(String presentedToken) {
        int dot = presentedToken.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(presentedToken.substring(0, dot)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Consumes a token and returns the user it belonged to, if it was usable. */
    public Optional<UUID> consume(String presentedToken) {
        List<Row> rows = jdbc.query("""
                SELECT id, user_id, revoked_at, expires_at
                FROM refresh_tokens WHERE token_hash = :hash
                """, new MapSqlParameterSource("hash", sha256(presentedToken)),
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("revoked_at") != null,
                        rs.getTimestamp("expires_at").toInstant()));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.get(0);

        if (row.revoked) {
            // Reuse of a consumed token. Either the token was stolen or a
            // session was cloned; either way both copies must stop working.
            revokeAllForUser(row.userId);
            return Optional.empty();
        }
        if (row.expiresAt.isBefore(Instant.now())) {
            return Optional.empty();
        }

        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", row.id));
        return Optional.of(row.userId);
    }

    public void revokeAllForUser(UUID userId) {
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now()
                WHERE user_id = :uid AND revoked_at IS NULL
                """, new MapSqlParameterSource("uid", userId));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Row(UUID id, UUID userId, boolean revoked, Instant expiresAt) {}
}
