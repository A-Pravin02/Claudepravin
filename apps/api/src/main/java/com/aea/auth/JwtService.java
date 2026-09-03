package com.aea.auth;

import com.aea.platform.Principal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Issues and verifies the short-lived access token.
 *
 * SECURITY: the token carries roles and permissions as claims so authorization
 * does not need a database round trip per request. The tradeoff is staleness --
 * a permission revoked mid-session stays effective until the access token
 * expires. That is why the access TTL is minutes, not hours: revocation is
 * enforced at refresh, and the refresh token can be revoked immediately.
 */
@Service
public class JwtService {

    private static final String ISSUER = "aea";
    private static final String CLAIM_TENANT = "tid";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${aea.auth.jwt-secret}") String secret,
                      @Value("${aea.auth.access-ttl-seconds:900}") long accessTtlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 requires at least 256 bits. A short secret is a silently weak
        // signature, so refuse to start rather than issue forgeable tokens.
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "aea.auth.jwt-secret must be at least 32 bytes. Generate one with: "
                    + "openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = Duration.ofSeconds(accessTtlSeconds);
    }

    public String issueAccessToken(Principal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(principal.userId().toString())
                .claim(CLAIM_TENANT, principal.tenantId().toString())
                .claim(CLAIM_ROLES, List.copyOf(principal.roles()))
                .claim(CLAIM_PERMS, List.copyOf(principal.permissions()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** Returns the principal a valid token describes, or empty for any invalid token. */
    public Optional<Principal> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(new Principal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_TENANT, String.class)),
                    null,
                    Set.copyOf(claims.get(CLAIM_ROLES, List.class)),
                    Set.copyOf(claims.get(CLAIM_PERMS, List.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Expired, tampered, wrong issuer, malformed -- all the same to the
            // caller. Never report which, and never log the token itself.
            return Optional.empty();
        }
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
