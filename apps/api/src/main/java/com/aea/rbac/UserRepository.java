package com.aea.rbac;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves which tenant an email belongs to, before any tenant context
     * exists. Reads only the routing table -- see V003 for why that table is
     * deliberately not tenant-scoped and holds no credentials.
     */
    public Optional<LoginRoute> resolveLoginRoute(String email) {
        var rows = jdbc.query(
                "SELECT user_id, tenant_id FROM user_directory WHERE email = :email",
                new MapSqlParameterSource("email", email),
                (rs, i) -> new LoginRoute(rs.getObject("user_id", UUID.class),
                                          rs.getObject("tenant_id", UUID.class)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The same routing lookup, keyed by user id -- used on token refresh. */
    public Optional<LoginRoute> resolveLoginRouteById(UUID userId) {
        var rows = jdbc.query(
                "SELECT user_id, tenant_id FROM user_directory WHERE user_id = :uid",
                new MapSqlParameterSource("uid", userId),
                (rs, i) -> new LoginRoute(rs.getObject("user_id", UUID.class),
                                          rs.getObject("tenant_id", UUID.class)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Loads a user with roles and permissions. Runs inside the caller's tenant
     * scope, so RLS applies: asking for a user id belonging to another tenant
     * returns empty rather than that user.
     */
    public Optional<UserAccount> findById(UUID userId) {
        var rows = jdbc.query("""
                SELECT id, tenant_id, email, password_hash, display_name, status,
                       failed_logins, last_login_at
                FROM users WHERE id = :id
                """, new MapSqlParameterSource("id", userId), (rs, i) -> map(rs));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UserAccount u = rows.get(0);
        return Optional.of(new UserAccount(u.id(), u.tenantId(), u.email(), u.passwordHash(),
                u.displayName(), u.status(), u.failedLogins(), u.lastLoginAt(),
                rolesOf(userId), permissionsOf(userId)));
    }

    private Set<String> rolesOf(UUID userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT r.name FROM roles r
                JOIN user_roles ur ON ur.role_id = r.id
                WHERE ur.user_id = :uid
                """, new MapSqlParameterSource("uid", userId), String.class));
    }

    private Set<String> permissionsOf(UUID userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.code
                FROM permissions p
                JOIN role_permissions rp ON rp.permission_id = p.id
                JOIN user_roles ur ON ur.role_id = rp.role_id
                WHERE ur.user_id = :uid
                """, new MapSqlParameterSource("uid", userId), String.class));
    }

    public void recordSuccessfulLogin(UUID userId) {
        jdbc.update("UPDATE users SET last_login_at = now(), failed_logins = 0 WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    public void recordFailedLogin(UUID userId) {
        jdbc.update("UPDATE users SET failed_logins = failed_logins + 1 WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    private UserAccount map(ResultSet rs) throws SQLException {
        var lastLogin = rs.getTimestamp("last_login_at");
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getInt("failed_logins"),
                lastLogin == null ? null : lastLogin.toInstant(),
                Set.of(), Set.of());
    }

    public record LoginRoute(UUID userId, UUID tenantId) {}
}
