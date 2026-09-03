package com.aea.tenancy;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Provisions the test database without a Spring context.
 *
 * The RLS tests connect with raw JDBC on purpose, so nothing in the framework
 * can be credited with enforcing isolation. That means Flyway has to be driven
 * explicitly here rather than by Boot's auto-configuration.
 */
final class TestDatabase {

    static final String URL =
            System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/aea_test");
    static final String APP_USER =
            System.getenv().getOrDefault("DB_USER", "aea_app");
    static final String APP_PASS =
            System.getenv().getOrDefault("DB_PASSWORD", "aea_app_dev_only");
    static final String OWNER_USER =
            System.getenv().getOrDefault("DB_MIGRATION_USER", "aea_owner");
    static final String OWNER_PASS =
            System.getenv().getOrDefault("DB_MIGRATION_PASSWORD", "aea_owner_dev_only");

    static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    private static boolean ready = false;

    private TestDatabase() {}

    /**
     * Idempotent: migrates, then seeds two tenants if they are absent.
     *
     * The fixture users use dedicated addresses so they never collide with the
     * demo users DemoDataSeeder creates -- a collision would leave the demo
     * accounts holding this fixture's unusable password hash, and every login
     * test would fail for a reason that has nothing to do with login.
     */
    static synchronized void ensureReady() throws SQLException {
        if (ready) return;

        Flyway.configure()
              .dataSource(URL, OWNER_USER, OWNER_PASS)
              .locations("classpath:db/migration")
              .cleanDisabled(false)
              .load()
              .migrate();

        // Seeding runs as the owner, which is still subject to FORCE RLS, so
        // each insert must declare the tenant it acts for.
        try (Connection c = DriverManager.getConnection(URL, OWNER_USER, OWNER_PASS);
             Statement s = c.createStatement()) {
            seedTenant(s, TENANT_A, "TechStore", "techstore", "rls-fixture-a@techstore.test", "RLS Fixture A");
            seedTenant(s, TENANT_B, "RivalCorp", "rivalcorp", "rls-fixture-b@rivalcorp.test", "RLS Fixture B");
        }
        ready = true;
    }

    private static void seedTenant(Statement s, String tenant, String name, String slug,
                                   String email, String display) throws SQLException {
        s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
        s.execute("INSERT INTO organizations (id, name, slug) VALUES ('"
                + tenant + "', '" + name + "', '" + slug + "') ON CONFLICT (slug) DO NOTHING");
        s.execute("INSERT INTO users (tenant_id, email, display_name, password_hash) VALUES ('"
                + tenant + "', '" + email + "', '" + display
                + "', 'seed-not-a-real-hash') ON CONFLICT (tenant_id, email) DO NOTHING");
    }

    static Connection appConnection() throws SQLException {
        Connection c = DriverManager.getConnection(URL, APP_USER, APP_PASS);
        c.setAutoCommit(false);
        return c;
    }
}
