package com.aea.persistence;

import com.aea.platform.TenantContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Scopes every transaction's database session to the caller's tenant, so
 * PostgreSQL's row-level security policies can enforce isolation.
 *
 * SECURITY-CRITICAL. Three decisions here are load-bearing:
 *
 * 1. It hooks the transaction manager rather than each repository. A per-query
 *    filter is only as good as the developer who remembers to apply it; this
 *    runs for every transaction whether or not anyone remembered.
 *
 * 2. It uses set_config(..., is_local => true), not SET LOCAL. PostgreSQL's SET
 *    statement takes no bind parameters, so SET LOCAL would require
 *    concatenating a value into SQL. set_config is an ordinary function call,
 *    so the tenant id travels as a bound parameter and can never be parsed as
 *    SQL. is_local scopes it to this transaction, so a pooled connection cannot
 *    carry one tenant's context into the next request that borrows it.
 *
 * 3. With no principal bound it sets the empty string rather than skipping the
 *    call. Skipping would leave whatever the previous transaction set; the
 *    empty string is treated as NULL by the policies (see V003), so unscoped
 *    work is fail-closed -- zero rows, never every row.
 */
public class TenantAwareTransactionManager extends DataSourceTransactionManager {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);

        String tenant = TenantContext.tenantId().map(UUID::toString).orElse("");
        try (PreparedStatement ps = con.prepareStatement(SET_TENANT)) {
            ps.setString(1, tenant);
            ps.execute();
        }
    }
}
