package com.aea.persistence;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class PersistenceConfig {

    /**
     * Replaces Boot's default transaction manager so no code path can open a
     * transaction without the tenant scope being applied.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareTransactionManager(dataSource);
    }

    /**
     * Fails fast if the application is configured to connect as the migration
     * owner.
     *
     * SECURITY-CRITICAL: aea_owner owns the tables, and a table owner bypasses
     * its own RLS policies unless FORCE is set. Connecting as the owner would
     * silently disable tenant isolation across the entire platform while every
     * test still passed. This is the single most dangerous misconfiguration in
     * the system, so it is a startup failure rather than a warning.
     */
    @Bean
    public RuntimeRoleGuard runtimeRoleGuard(DataSourceProperties properties) {
        return new RuntimeRoleGuard(properties.getUsername());
    }

    public static class RuntimeRoleGuard {
        public RuntimeRoleGuard(String username) {
            if (username != null && username.trim().equalsIgnoreCase("aea_owner")) {
                throw new IllegalStateException(
                        "Refusing to start: the application is configured to connect as "
                        + "'aea_owner', the schema owner. Tenant isolation would be silently "
                        + "disabled. Set DB_USER=aea_app.");
            }
        }
    }
}
