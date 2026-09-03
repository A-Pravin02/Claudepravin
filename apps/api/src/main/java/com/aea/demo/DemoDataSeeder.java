package com.aea.demo;

import com.aea.platform.Principal;
import com.aea.platform.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds demo users for the TechStore scenario.
 *
 * Users are created here rather than in a migration because their password
 * hashes must come from the application's own encoder at its configured cost,
 * not from a constant pasted into version control.
 *
 * Profile-gated and idempotent. The passwords are published in docs/SETUP.md
 * and are worthless outside a local demo; this component must never be active
 * in a deployment that holds real data.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final UUID TECHSTORE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIVALCORP = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PASSWORD = "demo1234";

    private record DemoUser(UUID tenant, String email, String name, String role, String region) {}

    private static final List<DemoUser> USERS = List.of(
            new DemoUser(TECHSTORE, "admin@techstore.test",   "Priya Nair",   "ORG_ADMIN",  null),
            new DemoUser(TECHSTORE, "asha@techstore.test",    "Asha Kumar",   "MANAGER",    "Chennai"),
            new DemoUser(TECHSTORE, "vikram@techstore.test",  "Vikram Rao",   "MANAGER",    "Bengaluru"),
            new DemoUser(TECHSTORE, "hr@techstore.test",      "Meera Iyer",   "HR_MANAGER", null),
            new DemoUser(TECHSTORE, "sam@techstore.test",     "Sam Fernandez","EMPLOYEE",   null),
            new DemoUser(TECHSTORE, "view@techstore.test",    "Dev Menon",    "VIEWER",     null),
            // Exists purely so cross-tenant isolation is tested against real
            // rows: every isolation test asks a TechStore user for these.
            new DemoUser(RIVALCORP, "bo@rivalcorp.test",      "Bo Lindqvist", "ORG_ADMIN",  null),
            new DemoUser(RIVALCORP, "kai@rivalcorp.test",     "Kai Osei",     "EMPLOYEE",   null));

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public DemoDataSeeder(NamedParameterJdbcTemplate jdbc, PasswordEncoder encoder,
                          TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String hash = encoder.encode(PASSWORD);
        int created = 0;
        for (DemoUser user : USERS) {
            created += seed(user, hash) ? 1 : 0;
        }
        log.info("Demo seed complete: {} user(s) created, {} already present",
                created, USERS.size() - created);
    }

    private boolean seed(DemoUser user, String passwordHash) {
        // Each tenant's rows are written under that tenant's scope: RLS applies
        // to this component exactly as it does to a request, which is the point.
        TenantContext.set(new Principal(UUID.randomUUID(), user.tenant(), null,
                Set.of(), Set.of()));
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                Integer existing = jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE email = :email",
                        new MapSqlParameterSource("email", user.email()), Integer.class);
                if (existing != null && existing > 0) {
                    return false;
                }

                UUID userId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO users (id, tenant_id, email, password_hash, display_name)
                        VALUES (:id, :tenant, :email, :hash, :name)
                        """, new MapSqlParameterSource()
                                .addValue("id", userId)
                                .addValue("tenant", user.tenant())
                                .addValue("email", user.email())
                                .addValue("hash", passwordHash)
                                .addValue("name", user.name()));

                jdbc.update("""
                        INSERT INTO user_roles (tenant_id, user_id, role_id)
                        SELECT :tenant, :user, r.id FROM roles r
                        WHERE r.name = :role
                          AND (r.tenant_id IS NULL OR r.tenant_id = :tenant)
                        """, new MapSqlParameterSource()
                                .addValue("tenant", user.tenant())
                                .addValue("user", userId)
                                .addValue("role", user.role()));

                if (user.region() != null) {
                    // A MANAGER sees only their own region. The PDP (M1.3)
                    // compiles this into SQL predicates and retrieval filters.
                    jdbc.update("""
                            INSERT INTO access_scopes
                                (tenant_id, subject_type, subject_id, resource_type,
                                 resource_key, predicate)
                            VALUES (:tenant, 'USER', :user, 'TABLE', 'sales',
                                    CAST(:predicate AS jsonb))
                            """, new MapSqlParameterSource()
                                    .addValue("tenant", user.tenant())
                                    .addValue("user", userId)
                                    .addValue("predicate",
                                            "{\"region\": {\"in\": [\"" + user.region() + "\"]}}"));
                }
                return true;
            }));
        } finally {
            TenantContext.clear();
        }
    }
}
