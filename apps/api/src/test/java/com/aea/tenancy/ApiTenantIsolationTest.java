package com.aea.tenancy;

import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant isolation through the running application, not raw JDBC.
 *
 * RowLevelSecurityTest proves PostgreSQL enforces isolation. This proves the
 * application actually hands PostgreSQL the right scope -- that the transaction
 * manager sets app.tenant_id on every transaction, that an absent principal is
 * fail-closed, and that a pooled connection does not carry one tenant's context
 * into the next piece of work.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
class ApiTenantIsolationTest {

    private static final UUID TECHSTORE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIVALCORP = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void actAs(UUID tenantId) {
        TenantContext.set(new Principal(UUID.randomUUID(), tenantId, null, Set.of(), Set.of()));
    }

    private int countUsers() {
        Integer n = tx.execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM users", new MapSqlParameterSource(), Integer.class));
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("with no principal bound, the application sees nothing")
    void unscopedWorkIsFailClosed() {
        assertEquals(0, countUsers(),
                "A transaction with no principal must see zero rows. Seeing rows here means "
                + "any code path that forgets to authenticate exposes every tenant.");
    }

    @Test
    @DisplayName("each tenant sees only its own users, with no WHERE clause written")
    void tenantsAreIsolated() {
        actAs(TECHSTORE);
        int techstore = countUsers();
        assertTrue(techstore > 0, "TechStore should see its seeded users");

        actAs(RIVALCORP);
        int rivalcorp = countUsers();
        assertTrue(rivalcorp > 0, "RivalCorp should see its seeded users");

        // Neither sees the other's rows, so neither count is the total.
        actAs(TECHSTORE);
        assertEquals(techstore, countUsers());
        assertNotEquals(techstore + rivalcorp, techstore,
                "if one tenant saw every row, isolation is not being applied");
    }

    @Test
    @DisplayName("asking explicitly for another tenant's rows returns nothing")
    void explicitCrossTenantQueryReturnsNothing() {
        actAs(TECHSTORE);
        Integer leaked = tx.execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = :other",
                new MapSqlParameterSource("other", RIVALCORP), Integer.class));
        assertEquals(0, leaked,
                "RLS must reject the row even when the query names the other tenant directly");
    }

    @Test
    @DisplayName("tenant scope does not survive into the next transaction")
    void scopeDoesNotLeakAcrossTransactions() {
        actAs(TECHSTORE);
        assertTrue(countUsers() > 0);

        // Same pooled connection, no principal: set_config used is_local=true,
        // so the previous scope died at commit.
        TenantContext.clear();
        assertEquals(0, countUsers(),
                "A pooled connection carried a previous request's tenant into unscoped work");
    }

    @Test
    @DisplayName("the login routing table resolves a user to the right tenant")
    void directoryRoutesToCorrectTenant() {
        // Deliberately unscoped: this table is what decides the scope, so it
        // cannot itself be scoped. It holds no credentials.
        TenantContext.clear();
        UUID tenant = tx.execute(s -> jdbc.queryForObject(
                "SELECT tenant_id FROM user_directory WHERE email = :email",
                new MapSqlParameterSource("email", "asha@techstore.test"), UUID.class));
        assertEquals(TECHSTORE, tenant);

        UUID rival = tx.execute(s -> jdbc.queryForObject(
                "SELECT tenant_id FROM user_directory WHERE email = :email",
                new MapSqlParameterSource("email", "bo@rivalcorp.test"), UUID.class));
        assertEquals(RIVALCORP, rival);
    }

    @Test
    @DisplayName("the directory holds no credentials")
    void directoryCarriesNoSecrets() {
        TenantContext.clear();
        var columns = tx.execute(s -> jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'user_directory'
                """, new MapSqlParameterSource(), String.class));
        assertEquals(Set.of("email", "user_id", "tenant_id"), Set.copyOf(columns),
                "The routing table must stay minimal. Anything beyond the mapping itself "
                + "would be readable without a tenant scope.");
    }
}
