# 07 — Milestone 1: Secure Foundation

**Duration:** ~2 weeks for 2 developers.
**Contains no AI whatsoever.** That is deliberate.

## Why this is the first milestone

The instinct is to start with the LLM call, because it demos well. That is the
wrong order for this product. The PDP and tenancy model are the components that
every later component depends on for correctness, and the only ones that cannot
be retrofitted — retrieval, SQL, and reasoning all take an `AccessConstraint`
as an input. Build the security boundary first and every later feature inherits
it. Build it fourth and every earlier feature has to be re-audited.

Additionally: a working auth + tenancy + audit skeleton is the shortest path to
a system where the *next* milestone is one component, not five.

## Scope

### Infrastructure
- `docker-compose.yml`: Postgres 16 + pgvector, api, web.
- Flyway migration `V001__foundation.sql`: organizations, users, roles,
  permissions, role_permissions, user_roles, access_scopes, audit_logs.
- Two DB roles: `aea_owner` (migrations) and `aea_app` (runtime, no
  `BYPASSRLS`, no DDL). RLS policies on every tenant-scoped table.
- GitHub Actions: build, test, ArchUnit, Flyway validate.

### Backend
- Spring Boot 3 skeleton with the package layout from `03-repository-structure.md`.
- `platform`: `RequestContext`, `Principal`, `TenantContext`, problem+json error handler,
  `X-Request-Id` filter.
- `persistence`: transaction listener that issues `SET LOCAL app.tenant_id`.
- `auth`: `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`.
  BCrypt, short-lived access JWT, rotating refresh tokens.
- `rbac`: seeded roles (SUPER_ADMIN, ORG_ADMIN, MANAGER, EMPLOYEE, VIEWER) and
  the permission catalogue (READ_SALES, READ_INVENTORY, READ_CUSTOMER, READ_HR,
  READ_SALARY, MANAGE_RULES, MANAGE_USERS, VIEW_AUDIT, VIEW_TRACE).
- `pdp`: `PolicyDecisionPoint` + `AccessConstraint`, resolving role permissions
  and `access_scopes` into row predicates. **No consumers yet — the interface
  and its tests are the deliverable.**
- `audit`: `AuditWriter` with hash chaining; synchronous writes for security
  events. `GET /api/v1/admin/audit` for ORG_ADMIN.
- `GET /health`, `GET /api/v1/me`.

### Frontend
- Vite + React + TS, TanStack Query, router.
- Login page, authenticated shell, session refresh, "who am I" panel showing
  tenant, roles, permissions.

### Data
- `data/demo/techstore/`: 2 tenants (TechStore + a second tenant used purely as
  the cross-tenant leak target), ~8 users spanning all 5 roles.

## Exit criteria — all must be automated tests

1. A TechStore `EMPLOYEE` token returns zero rows for every Tenant-B resource,
   at every endpoint.
2. The same, with the application-level tenant predicate removed — RLS alone
   must still return zero rows.
3. The same, with RLS disabled — the application predicate alone must still
   return zero rows.
4. `PolicyDecisionPoint` unit tests cover the full 5-roles × all-permissions
   matrix, plus `access_scopes` row-predicate compilation.
5. Every login, failed login, and denial writes exactly one audit row; the hash
   chain verifies over the whole table.
6. ArchUnit: nothing outside `pdp` constructs an `AccessConstraint`.
7. `docker compose up` gives a working login flow from a clean checkout.

## Explicitly not in Milestone 1

No LLM, no embeddings, no documents, no SQL generation, no rules, no router.
If any of those appear in the milestone-1 PR, they are out of scope and the
security foundation is being compromised for a demo.

## What comes next

**Milestone 2** (~1 week): model gateway + cost ledger +
`POST /api/v1/query` returning a plain answer with a complete audit and cost
record. This establishes the request envelope that every subsequent path fills
in.

**Milestone 3** runs two tracks in parallel: ingestion/retrieval (Track C) and
the rule engine (Track B). They share no dependencies and can proceed
independently.
