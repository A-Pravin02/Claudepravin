-- =============================================================================
-- V003
--   1. Harden the RLS policies against an empty tenant setting.
--   2. Seed the two demo organizations and their tenant-specific roles.
--
-- Users are NOT seeded here: their passwords must be hashed with the
-- application's own encoder rather than a constant pasted into version
-- control. See DemoDataSeeder, which runs under the 'demo' profile.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Treat an empty app.tenant_id as absent
-- -----------------------------------------------------------------------------
-- The transaction manager sets app.tenant_id on every transaction, using the
-- empty string when no principal is bound. Setting it explicitly (rather than
-- skipping the call) guarantees a pooled connection cannot carry a previous
-- request's tenant. But ''::uuid raises invalid-input rather than yielding
-- NULL, which would turn unscoped access into a 500 instead of an empty
-- result -- and an error that leaks a database message.
--
-- NULLIF maps both "unset" and "explicitly empty" to NULL. Every policy
-- predicate then evaluates to NULL, and no rows match. Fail-closed either way.

CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

COMMENT ON FUNCTION current_tenant_id() IS
    'The tenant scope of the current transaction, or NULL when unscoped. '
    'NULL makes every RLS predicate NULL, so unscoped access returns no rows.';

DROP POLICY tenant_isolation ON organizations;
CREATE POLICY tenant_isolation ON organizations
    USING (id = current_tenant_id());

DROP POLICY tenant_isolation ON users;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP POLICY tenant_isolation ON refresh_tokens;
CREATE POLICY tenant_isolation ON refresh_tokens
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP POLICY tenant_isolation ON roles;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id IS NULL OR tenant_id = current_tenant_id());

DROP POLICY tenant_isolation ON user_roles;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP POLICY tenant_isolation ON access_scopes;
CREATE POLICY tenant_isolation ON access_scopes
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

DROP POLICY tenant_isolation ON audit_logs;
CREATE POLICY tenant_isolation ON audit_logs
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

GRANT EXECUTE ON FUNCTION current_tenant_id() TO aea_app;

-- -----------------------------------------------------------------------------
-- 1b. Login routing
-- -----------------------------------------------------------------------------
-- Authentication has a genuine ordering problem: RLS scopes every query to a
-- tenant, but at login there is no token yet, so there is no tenant to scope
-- to. Looking the user up in `users` with no tenant context returns zero rows
-- -- correctly, and unhelpfully.
--
-- The alternatives were worse. A SECURITY DEFINER function bypassing RLS
-- creates a standing hole and needs a BYPASSRLS role, which needs superuser at
-- provisioning time in three separate places. A policy exception keyed on a
-- session setting is self-serve: the application role can set that setting
-- itself and read every tenant's users.
--
-- Instead: a routing table holding only what is needed to decide WHICH tenant
-- a login belongs to. No password hash, no display name, no PII beyond the
-- email that was submitted anyway. Login reads this, binds the tenant, and
-- every subsequent query -- including loading the user and verifying the
-- password -- runs tenant-scoped under RLS like everything else.

CREATE TABLE user_directory (
    email      citext PRIMARY KEY,
    user_id    uuid   NOT NULL,
    tenant_id  uuid   NOT NULL
);

COMMENT ON TABLE user_directory IS
    'Login routing only: email -> (user, tenant). Deliberately not tenant-scoped, '
    'because it is what resolves the tenant. Holds no credentials.';

-- Maintained by trigger, never by the application. The trigger is SECURITY
-- DEFINER and aea_app holds SELECT only, so the application cannot repoint a
-- login at another tenant even if application code is compromised.
CREATE FUNCTION sync_user_directory() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM user_directory WHERE user_id = OLD.id;
        RETURN OLD;
    END IF;

    -- Covers an email change: drop the stale row before writing the new one.
    DELETE FROM user_directory WHERE user_id = NEW.id;
    INSERT INTO user_directory (email, user_id, tenant_id)
    VALUES (NEW.email, NEW.id, NEW.tenant_id)
    ON CONFLICT (email) DO UPDATE
        SET user_id = EXCLUDED.user_id, tenant_id = EXCLUDED.tenant_id;
    RETURN NEW;
END $$;

CREATE TRIGGER users_sync_directory
    AFTER INSERT OR UPDATE OF email, tenant_id OR DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION sync_user_directory();

GRANT SELECT ON user_directory TO aea_app;

-- -----------------------------------------------------------------------------
-- 2. Demo organizations
-- -----------------------------------------------------------------------------
-- Two tenants on purpose. RivalCorp exists so cross-tenant isolation can be
-- tested against real data rather than asserted: every isolation test asks
-- TechStore's users for RivalCorp's rows and expects nothing back.
--
-- FORCE ROW LEVEL SECURITY applies to the migration role too, so each insert
-- must declare the tenant it acts for.

SELECT set_config('app.tenant_id', '11111111-1111-1111-1111-111111111111', false);
INSERT INTO organizations (id, name, slug, settings) VALUES
    ('11111111-1111-1111-1111-111111111111', 'TechStore', 'techstore',
     '{"conflict_policy": ["SECURITY", "RULE", "DATA", "NEURAL"], "max_cost_micros_per_query": 100000}'::jsonb)
ON CONFLICT (slug) DO NOTHING;

-- TechStore keeps salary access in a dedicated role. No system role carries
-- READ_SALARY (see V002), so "show me all employee salaries" is denied
-- structurally rather than by a special case in application code.
INSERT INTO roles (tenant_id, name, description, is_system) VALUES
    ('11111111-1111-1111-1111-111111111111', 'HR_MANAGER',
     'Human resources. The only role holding READ_SALARY.', false)
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('READ_HR', 'READ_SALARY', 'READ_POLICY')
WHERE r.tenant_id = '11111111-1111-1111-1111-111111111111' AND r.name = 'HR_MANAGER'
ON CONFLICT DO NOTHING;

SELECT set_config('app.tenant_id', '22222222-2222-2222-2222-222222222222', false);
INSERT INTO organizations (id, name, slug, settings) VALUES
    ('22222222-2222-2222-2222-222222222222', 'RivalCorp', 'rivalcorp', '{}'::jsonb)
ON CONFLICT (slug) DO NOTHING;

SELECT set_config('app.tenant_id', '', false);
