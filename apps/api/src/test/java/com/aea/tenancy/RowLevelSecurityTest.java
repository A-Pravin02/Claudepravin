package com.aea.tenancy;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves tenant isolation is enforced by PostgreSQL itself, not by application
 * code. These tests connect with raw JDBC as the runtime role (aea_app) so that
 * no Spring repository, no ORM filter, and no service-layer predicate can be
 * credited with the result.
 *
 * The exit criterion for Milestone 1 is that isolation holds three ways:
 *   1. normally,
 *   2. with the application predicate absent (RLS alone must hold)  <- here,
 *   3. with RLS disabled (the application predicate alone must hold).
 * This class covers (1) and (2). Case (3) arrives with the repository layer.
 */
class RowLevelSecurityTest {

    private static final UUID TENANT_A = UUID.fromString(TestDatabase.TENANT_A);
    private static final UUID TENANT_B = UUID.fromString(TestDatabase.TENANT_B);

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        TestDatabase.ensureReady();
    }

    private Connection app() throws SQLException {
        return TestDatabase.appConnection();
    }

    /**
     * Mirrors what the application does per transaction.
     *
     * SECURITY: PostgreSQL's SET statement does not accept bind parameters, so
     * the obvious "SET LOCAL app.tenant_id = " + id would have to concatenate a
     * value into SQL. set_config() is a normal function call, so the tenant id
     * travels as a bound parameter and can never be interpreted as SQL. The
     * third argument (is_local = true) scopes it to the transaction, so a
     * pooled connection cannot carry one tenant's context into the next
     * request that borrows it.
     */
    private void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps =
                     c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private int countUsers(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM users")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("no tenant context yields zero rows, not every row")
    void failsClosedWhenTenantUnset() throws Exception {
        try (Connection c = app()) {
            assertEquals(0, countUsers(c),
                    "An unset app.tenant_id must be fail-closed. Seeing rows here means "
                    + "a missing SET LOCAL exposes the whole platform.");
        }
    }

    @Test
    @DisplayName("a tenant sees only its own rows, with no WHERE clause at all")
    void seesOnlyOwnTenant() throws Exception {
        try (Connection c = app()) {
            setTenant(c, TENANT_A);
            int a = countUsers(c);
            assertTrue(a > 0, "Tenant A should see its own seeded users");

            setTenant(c, TENANT_B);
            int b = countUsers(c);
            assertTrue(b > 0, "Tenant B should see its own seeded users");

            setTenant(c, TENANT_A);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM users WHERE tenant_id = '" + TENANT_B + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1),
                        "Explicitly asking for another tenant's rows must return nothing.");
            }
        }
    }

    @Test
    @DisplayName("cross-tenant INSERT is rejected by the WITH CHECK policy")
    void cannotWriteIntoAnotherTenant() throws Exception {
        try (Connection c = app()) {
            setTenant(c, TENANT_A);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (tenant_id, email, display_name) VALUES (?,?,?)")) {
                ps.setObject(1, TENANT_B);
                ps.setString(2, "smuggled@example.test");
                ps.setString(3, "Smuggled");
                assertThrows(SQLException.class, ps::execute,
                        "Writing a row belonging to another tenant must be refused.");
            }
        }
    }

    @Test
    @DisplayName("the runtime role cannot bypass RLS")
    void runtimeRoleHasNoBypass() throws Exception {
        try (Connection c = app();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = current_user")) {
            rs.next();
            assertFalse(rs.getBoolean("rolbypassrls"),
                    "BYPASSRLS on the runtime role disables every policy in this migration.");
            assertFalse(rs.getBoolean("rolsuper"),
                    "A superuser runtime role ignores RLS entirely.");
        }
    }

    @Test
    @DisplayName("the audit log is append-only for the application")
    void auditLogIsAppendOnly() throws Exception {
        try (Connection c = app()) {
            setTenant(c, TENANT_A);
            try (Statement s = c.createStatement()) {
                assertThrows(SQLException.class,
                        () -> s.execute("UPDATE audit_logs SET decision = 'ALLOW'"),
                        "The application must not be able to rewrite audit history.");
            }
            c.rollback();
            setTenant(c, TENANT_A);
            try (Statement s = c.createStatement()) {
                assertThrows(SQLException.class,
                        () -> s.execute("DELETE FROM audit_logs"),
                        "The application must not be able to erase audit history.");
            }
        }
    }

    @Test
    @DisplayName("the runtime role holds no DDL rights")
    void runtimeRoleCannotAlterSchema() throws Exception {
        try (Connection c = app(); Statement s = c.createStatement()) {
            assertThrows(SQLException.class,
                    () -> s.execute("CREATE TABLE rls_bypass_attempt (x int)"),
                    "DDL from the application role would allow dropping policies.");
        }
    }
}
