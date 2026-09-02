-- =============================================================================
-- V001 Foundation: tenancy, identity, access control, audit.
--
-- Every tenant-scoped table carries tenant_id and is protected by Row Level
-- Security. Isolation is an engine guarantee, not a code-review convention:
-- a query that forgets its WHERE clause returns zero rows, not every row.
--
-- The session variable app.tenant_id is set per transaction by the
-- application (SET LOCAL). When it is unset, current_setting(...,true)
-- returns NULL, every policy predicate evaluates to NULL, and no rows are
-- visible. Fail-closed by construction.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive email

-- -----------------------------------------------------------------------------
-- Tenancy
-- -----------------------------------------------------------------------------
CREATE TABLE organizations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    slug        text        NOT NULL UNIQUE,
    status      text        NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    settings    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN organizations.settings IS
    'Per-tenant policy: conflict resolution order, cost caps, retention.';

-- -----------------------------------------------------------------------------
-- Identity
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email          citext      NOT NULL,
    password_hash  text,        -- NULL when the user is SSO-only (V2)
    display_name   text        NOT NULL,
    status         text        NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    failed_logins  int         NOT NULL DEFAULT 0,
    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  bytea       NOT NULL UNIQUE,  -- SHA-256; the token itself is never stored
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid REFERENCES refresh_tokens(id),  -- rotation chain
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

COMMENT ON COLUMN refresh_tokens.token_hash IS
    'SECURITY: only the hash is persisted, so a database read cannot mint sessions.';

-- -----------------------------------------------------------------------------
-- Access control
-- -----------------------------------------------------------------------------
-- Global reference data, identical for every tenant. Deliberately not
-- tenant-scoped and not RLS-protected: knowing that READ_SALARY exists
-- discloses nothing.
CREATE TABLE permissions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code        text NOT NULL UNIQUE,
    category    text NOT NULL CHECK (category IN ('DATA', 'ADMIN', 'ACTION')),
    description text NOT NULL
);

CREATE TABLE roles (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid REFERENCES organizations(id) ON DELETE CASCADE,  -- NULL = platform role
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    is_system   boolean NOT NULL DEFAULT false  -- system roles cannot be deleted
);
CREATE UNIQUE INDEX idx_roles_tenant_name ON roles (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), name);

CREATE TABLE role_permissions (
    role_id       uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- tenant_id is denormalised here so the RLS policy needs no join.
-- Same principle applied later to document_chunks: a security predicate that
-- requires a join is a security predicate someone will eventually drop.
CREATE TABLE user_roles (
    tenant_id  uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    granted_by uuid REFERENCES users(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_tenant ON user_roles (tenant_id);

-- Row-level data scoping, e.g. a MANAGER restricted to region = 'Chennai'.
-- The PDP compiles these into SQL predicates and retrieval filters.
CREATE TABLE access_scopes (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    subject_type  text NOT NULL CHECK (subject_type IN ('USER', 'ROLE')),
    subject_id    uuid NOT NULL,
    resource_type text NOT NULL CHECK (resource_type IN ('TABLE', 'DOCUMENT_CLASS')),
    resource_key  text NOT NULL,
    predicate     jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_access_scopes_lookup ON access_scopes (tenant_id, subject_type, subject_id);

COMMENT ON COLUMN access_scopes.predicate IS
    'Closed grammar, e.g. {"region": {"in": ["Chennai"]}}. Never an expression language.';

-- -----------------------------------------------------------------------------
-- Audit: append-only, hash-chained
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id              bigserial PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    request_id      uuid        NOT NULL,
    user_id         uuid,
    event_type      text        NOT NULL,
    event_action    text        NOT NULL,
    resource_type   text,
    resource_id     text,
    decision        text CHECK (decision IN ('ALLOW', 'DENY')),
    decision_reason text,
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash       bytea,
    entry_hash      bytea       NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant_time ON audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_tenant_type ON audit_logs (tenant_id, event_type, occurred_at DESC);
CREATE INDEX idx_audit_request ON audit_logs (request_id);

COMMENT ON COLUMN audit_logs.entry_hash IS
    'H(prev_hash || canonical_json(entry)). Tamper evidence without an external ledger.';

-- =============================================================================
-- Row Level Security
-- =============================================================================
-- FORCE matters: without it the table owner bypasses its own policies, which
-- would make every test pass while providing no protection in production.

ALTER TABLE organizations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations  FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organizations
    USING (id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE users          ENABLE ROW LEVEL SECURITY;
ALTER TABLE users          FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refresh_tokens
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Platform roles (tenant_id IS NULL) are visible to every tenant by design.
ALTER TABLE roles          ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles          FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE user_roles     ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles     FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE access_scopes  ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_scopes  FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access_scopes
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE audit_logs     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs     FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_logs
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- =============================================================================
-- Grants: least privilege for the runtime role
-- =============================================================================
GRANT USAGE ON SCHEMA public TO aea_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    organizations, users, refresh_tokens, roles, user_roles, access_scopes
    TO aea_app;

GRANT SELECT ON permissions, role_permissions TO aea_app;

-- Append-only: no UPDATE, no DELETE. The audit trail cannot be edited by the
-- application even if application code is compromised.
GRANT SELECT, INSERT ON audit_logs TO aea_app;
GRANT USAGE, SELECT ON SEQUENCE audit_logs_id_seq TO aea_app;
