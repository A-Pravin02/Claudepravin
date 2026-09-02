-- Runs once, as superuser, at cluster init.
--
-- SECURITY-CRITICAL: two roles with different powers.
--   aea_owner : owns the schema, runs migrations (DDL).
--   aea_app   : the application runtime role. No DDL, and crucially NO
--               BYPASSRLS and NOT the table owner, so Row Level Security
--               actually applies to it. A table owner implicitly bypasses
--               RLS unless FORCE is set; we set FORCE as well, belt and braces.
--
-- If the application ever connects as aea_owner, tenant isolation is silently
-- disabled. That is the single most dangerous misconfiguration in this system.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aea_owner') THEN
    CREATE ROLE aea_owner LOGIN PASSWORD 'aea_owner_dev_only' NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aea_app') THEN
    CREATE ROLE aea_app LOGIN PASSWORD 'aea_app_dev_only' NOBYPASSRLS NOCREATEDB NOCREATEROLE NOSUPERUSER;
  END IF;
END
$$;

-- Dev credentials only. Real deployments inject these from a secret manager.
