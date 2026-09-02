# Setup

What to install, in what order, and what to deliberately skip.

## What you do NOT need

Worth stating first, because the project name misleads.

**No GPU. No CUDA. No PyTorch/TensorFlow. No training datasets. No ML
environment of any kind.** Nothing in this platform trains a neural network.
Every neural component is inference against a pretrained model over an API:

| Component | Trained here? |
| --- | --- |
| Intent classification | No -- prompted API call |
| Entity / fact extraction | No -- prompted API call, constrained output |
| Answer generation | No -- prompted API call |
| Embeddings | No -- hosted embedding model |
| Complexity engine | **No model at all** -- deterministic feature scoring |
| Risk engine | **No model at all** -- rules and heuristics |
| Rule engine / inference | **No model at all** -- pure logic |

The engineering is in orchestration, authorization, and verification around
bought inference. See `docs/research/open-questions.md` for the one place
training may eventually appear (a learned router in V3, trained on the audit
log this system produces as a byproduct).

Also skip for now: Redis, Kubernetes, a dedicated vector database, cloud
hosting, a domain, Auth0/Keycloak, observability SaaS. All are V2+ in
`docs/architecture/01-roadmap.md`. Adding them now costs configuration
surface and money while teaching you nothing about whether the core bet works.

## Prerequisites

Pick one path.

### Path A -- Docker (recommended)

Install Docker Desktop (or Docker Engine + Compose v2). That is the entire
list; Postgres, pgvector, the API, and the web app all come up together.

```bash
cp .env.example .env
docker compose up --build
```

Expected: Postgres healthy, Flyway applies V001 and V002, API on :8080,
web on :5173.

Verify:
```bash
curl -s localhost:8080/actuator/health     # {"status":"UP"}
open http://localhost:5173
```

### Path B -- no Docker

| Tool | Version | Note |
| --- | --- | --- |
| JDK | 21 | Temurin recommended |
| Maven | 3.9+ | |
| Node | 22 | |
| PostgreSQL | 16 | **with the pgvector extension** -- easy to miss |

```bash
cp .env.example .env
make db-init      # creates aea_owner / aea_app and both databases
make test         # migrations, ArchUnit boundaries, RLS suite
make api          # terminal 1
make web          # terminal 2
```

`make db-init` grants `aea_owner` ownership of the databases. That matters:
`CREATE EXTENSION` needs the database-level CREATE privilege, and granting
only schema rights is what broke CI run #1.

## Verifying the security foundation

The point of Milestone 1 is that tenant isolation is enforced by PostgreSQL,
not by application code. Confirm it yourself:

```bash
cd apps/api && mvn -B verify
```

Nine tests. The ones that matter assert failure:

| Check | Expected |
| --- | --- |
| Query with no tenant context | 0 rows (fail-closed) |
| Tenant A querying tenant B's id | 0 rows |
| Cross-tenant INSERT | rejected by WITH CHECK |
| UPDATE / DELETE on audit_logs | permission denied |
| DDL as the runtime role | permission denied |
| `rolbypassrls` on aea_app | false |

If any of these *passes* where it should fail, isolation is broken --
treat it as a stop-work item, not a flaky test.

## Secrets

- `.env` is gitignored. Every real credential lives there and nowhere else.
- CI secrets go in GitHub repository settings, never in `ci.yml`.
- **CI must never call a live model.** Every push would cost money and the
  suite would flake nondeterministically. `LLM_PROVIDER=stub` is the CI
  default; live-model tests run on demand.
- The committed database passwords (`*_dev_only`) are local conveniences.
  Rotate them before anything is network-reachable.

## When you need API keys

Not for Milestone 1. Authentication, RBAC, the Policy Decision Point, and
audit involve no models.

At M2 (model gateway) you need one LLM provider key and one embedding
provider key -- possibly the same provider. Put them in `.env` and set
`LLM_PROVIDER` / `EMBEDDING_PROVIDER` accordingly.

At V1 evaluation you additionally need Python 3.12 + uv, and a budget for
benchmark runs: four systems x ~150 queries x repeats, where System C
(always-on neuro-symbolic) is expensive by design. Estimate it before
starting the run rather than discovering it midway.

## Troubleshooting

**`permission denied to create extension "pgcrypto"`** -- `aea_owner` lacks
database-level CREATE. Run `ALTER DATABASE <db> OWNER TO aea_owner;` as a
superuser.

**Tests see 0 rows everywhere, including where rows are expected** -- the
seed step did not run. `TestDatabase.ensureReady()` migrates and seeds; check
that `aea_owner` can connect.

**Tests see rows with no tenant set** -- serious. Either `aea_app` has
BYPASSRLS/superuser, or the app is connecting as `aea_owner`. Check
`DB_USER` in `.env`.

**`SET LOCAL app.tenant_id = ?` syntax error** -- PostgreSQL's SET takes no
bind parameters. Use `set_config('app.tenant_id', ?, true)`, which is also
injection-safe.
