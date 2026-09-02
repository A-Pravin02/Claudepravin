# 02 — Database Schema

PostgreSQL 16 + pgvector. Single logical database, shared schema, `tenant_id`
on every tenant-scoped table, enforced by **Row Level Security**, not by
application discipline.

## 1. Isolation model

Three models were considered:

| Model | Isolation | Ops cost | Verdict |
| --- | --- | --- | --- |
| Database per tenant | Strongest | High (N migrations, N pools) | V3, for regulated/on-prem tenants |
| Schema per tenant | Strong | Medium, degrades past ~100 tenants | Rejected |
| Shared schema + RLS | Good, enforced by the engine | Low | **Chosen for MVP–V2** |

RLS is chosen because it moves isolation from "every developer remembers the
`WHERE` clause" to "the database refuses." The application sets
`SET LOCAL app.tenant_id` at transaction start; every policy reads it.
A missing `tenant_id` yields zero rows rather than all rows.

**Security-critical:** the application's runtime role must NOT have `BYPASSRLS`
and must not be the table owner. Migrations run as a separate owner role.

```sql
-- applied to every tenant-scoped table
ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <t> FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON <t>
  USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

## 2. Identity and access

```sql
CREATE TABLE organizations (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name           text NOT NULL,
  slug           text NOT NULL UNIQUE,
  status         text NOT NULL DEFAULT 'ACTIVE',
  settings       jsonb NOT NULL DEFAULT '{}',   -- conflict policy, cost caps
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES organizations(id),
  email          citext NOT NULL,
  password_hash  text,                          -- null when SSO-only (V2)
  display_name   text NOT NULL,
  status         text NOT NULL DEFAULT 'ACTIVE',
  last_login_at  timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);

CREATE TABLE roles (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid REFERENCES organizations(id),  -- null = platform role
  name           text NOT NULL,                      -- ORG_ADMIN, MANAGER, ...
  description    text,
  UNIQUE (tenant_id, name)
);

CREATE TABLE permissions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code           text NOT NULL UNIQUE,          -- READ_SALES, READ_SALARY, ...
  category       text NOT NULL,                 -- DATA | ADMIN | ACTION
  description    text
);

CREATE TABLE role_permissions (
  role_id        uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission_id  uuid NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role_id        uuid NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  granted_by     uuid REFERENCES users(id),
  granted_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_id)
);

-- Row-level data scoping, e.g. a MANAGER limited to region = 'Chennai'.
-- Consumed by the PDP and compiled into SQL predicates and retrieval filters.
CREATE TABLE access_scopes (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES organizations(id),
  subject_type   text NOT NULL,                 -- USER | ROLE
  subject_id     uuid NOT NULL,
  resource_type  text NOT NULL,                 -- TABLE | DOCUMENT_CLASS
  resource_key   text NOT NULL,                 -- 'sales' | 'HR_CONFIDENTIAL'
  predicate      jsonb NOT NULL                 -- {"region":{"in":["Chennai"]}}
);
```

## 3. Knowledge — documents and vectors

```sql
CREATE TABLE documents (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES organizations(id),
  title             text NOT NULL,
  source_uri        text,
  mime_type         text,
  checksum          text NOT NULL,
  classification    text NOT NULL DEFAULT 'INTERNAL',
                    -- PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED
  required_permission text,                     -- e.g. READ_HR
  status            text NOT NULL DEFAULT 'PENDING',
  page_count        int,
  uploaded_by       uuid REFERENCES users(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, checksum)
);

CREATE TABLE document_chunks (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES organizations(id),
  document_id       uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  chunk_index       int NOT NULL,
  content           text NOT NULL,
  content_tsv       tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
  token_count       int,
  page_from         int,
  page_to           int,
  -- denormalised from documents so the security filter needs no join
  classification    text NOT NULL,
  required_permission text,
  embedding         vector(1536),
  metadata          jsonb NOT NULL DEFAULT '{}',
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, chunk_index)
);

CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON document_chunks USING gin (content_tsv);
CREATE INDEX ON document_chunks (tenant_id, classification);
```

Classification is **denormalised onto the chunk deliberately**: the security
predicate must be evaluable in the same index scan as the vector search. A
join here is a filter that someone eventually forgets.

## 4. Structured data sources

```sql
CREATE TABLE data_sources (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES organizations(id),
  name           text NOT NULL,
  kind           text NOT NULL,                 -- POSTGRES | MYSQL | ...
  connection_ref text NOT NULL,                 -- secret manager key, never a DSN
  read_only      boolean NOT NULL DEFAULT true,
  status         text NOT NULL DEFAULT 'ACTIVE',
  UNIQUE (tenant_id, name)
);

-- The allowlist. Anything absent here is invisible to NL→SQL.
CREATE TABLE data_source_tables (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  data_source_id uuid NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
  tenant_id      uuid NOT NULL,
  table_name     text NOT NULL,
  description    text,                          -- fed to the SQL prompt
  tenant_column  text,                          -- predicate injection target
  enabled        boolean NOT NULL DEFAULT false,
  UNIQUE (data_source_id, table_name)
);

CREATE TABLE data_source_columns (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  table_id       uuid NOT NULL REFERENCES data_source_tables(id) ON DELETE CASCADE,
  column_name    text NOT NULL,
  data_type      text NOT NULL,
  sensitivity    text NOT NULL DEFAULT 'NORMAL', -- NORMAL | PII | FINANCIAL | SECRET
  required_permission text,                      -- READ_SALARY on employees.salary
  enabled        boolean NOT NULL DEFAULT true,
  UNIQUE (table_id, column_name)
);
```

## 5. Symbolic layer

```sql
CREATE TABLE rules (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES organizations(id),
  rule_key       text NOT NULL,                 -- 'R003'
  name           text NOT NULL,
  description    text,
  domain         text NOT NULL,                 -- RETURNS | INVENTORY | HR
  definition     jsonb NOT NULL,                -- conditions + action, see below
  priority       int NOT NULL DEFAULT 100,
  authoritative  boolean NOT NULL DEFAULT true, -- overrides neural output
  enabled        boolean NOT NULL DEFAULT true,
  version        int NOT NULL DEFAULT 1,
  created_by     uuid REFERENCES users(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, rule_key, version)
);

CREATE TABLE rule_versions (          -- immutable audit of rule edits
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id        uuid NOT NULL REFERENCES rules(id),
  version        int NOT NULL,
  definition     jsonb NOT NULL,
  changed_by     uuid REFERENCES users(id),
  changed_at     timestamptz NOT NULL DEFAULT now()
);

-- Typed facts with provenance. Flat by design; a graph only if benchmarks
-- prove multi-hop traversal is the bottleneck.
CREATE TABLE knowledge_entities (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES organizations(id),
  entity_type    text NOT NULL,                 -- Customer | Product | Order
  external_id    text,
  attributes     jsonb NOT NULL DEFAULT '{}',
  UNIQUE (tenant_id, entity_type, external_id)
);

CREATE TABLE knowledge_relationships (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  subject_id     uuid NOT NULL REFERENCES knowledge_entities(id),
  predicate      text NOT NULL,                 -- purchased | belongs_to
  object_id      uuid REFERENCES knowledge_entities(id),
  object_value   jsonb,                         -- literal object
  valid_from     timestamptz,
  valid_to       timestamptz,
  provenance     jsonb NOT NULL,                -- {source, chunk_id|sql, extractor, confidence}
  created_at     timestamptz NOT NULL DEFAULT now()
);
```

`rules.definition` shape:

```json
{
  "conditions": {
    "all": [
      {"field": "purchase_age_days", "op": "LTE", "value": 30},
      {"field": "product.category", "op": "NEQ", "value": "NON_RETURNABLE"}
    ]
  },
  "action": {"assert": {"field": "return_eligible", "value": true}},
  "explanation": "Returns are permitted within 30 days for returnable goods."
}
```

Conditions are a closed grammar (`all`/`any`/`not`, a fixed operator set, typed
literals). **Security-critical:** no expression language, no scripting, no
`eval`. A rule is data, and the evaluator is a total function over it.

## 6. Query lifecycle and audit

```sql
CREATE TABLE queries (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES organizations(id),
  user_id            uuid NOT NULL REFERENCES users(id),
  conversation_id    uuid,
  raw_query          text NOT NULL,
  normalized_query   text,
  intent             text,
  complexity_score   numeric(4,3),
  complexity_features jsonb,
  risk_score         numeric(4,3),
  risk_factors       jsonb,
  created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE query_routes (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  query_id           uuid NOT NULL REFERENCES queries(id) ON DELETE CASCADE,
  tenant_id          uuid NOT NULL,
  planned_route      text NOT NULL,
  final_route        text NOT NULL,             -- differs after escalation
  escalation_count   int NOT NULL DEFAULT 0,
  escalation_reason  text,
  plan               jsonb NOT NULL,            -- full ExecutionPlan
  budget_tokens      int,
  budget_steps       int,
  model_tier         text,
  security_level     text
);

CREATE TABLE reasoning_traces (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  query_id           uuid NOT NULL REFERENCES queries(id) ON DELETE CASCADE,
  tenant_id          uuid NOT NULL,
  step_index         int NOT NULL,
  step_type          text NOT NULL,             -- RETRIEVE|SQL|EXTRACT|INFER|VERIFY
  input_summary      jsonb,
  output_summary     jsonb,
  facts_asserted     jsonb,
  rules_fired        jsonb,                     -- [{rule_key, version, bindings}]
  conflicts          jsonb,
  latency_ms         int,
  UNIQUE (query_id, step_index)
);

CREATE TABLE query_results (
  query_id           uuid PRIMARY KEY REFERENCES queries(id) ON DELETE CASCADE,
  tenant_id          uuid NOT NULL,
  answer             text,
  answer_status      text NOT NULL,             -- ANSWERED|DENIED|INSUFFICIENT|ERROR
  citations          jsonb,
  explanation        jsonb,
  system_confidence  numeric(4,3),
  confidence_signals jsonb,
  verification       jsonb,                     -- per-check pass/fail
  total_latency_ms   int
);

CREATE TABLE cost_records (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  query_id           uuid NOT NULL REFERENCES queries(id) ON DELETE CASCADE,
  tenant_id          uuid NOT NULL,
  stage              text NOT NULL,             -- INTENT|SQLGEN|RAG|FUSION|VERIFY
  provider           text,
  model              text,
  input_tokens       int NOT NULL DEFAULT 0,
  output_tokens      int NOT NULL DEFAULT 0,
  embedding_tokens   int NOT NULL DEFAULT 0,
  cost_micros        bigint NOT NULL DEFAULT 0, -- integer money, never float
  created_at         timestamptz NOT NULL DEFAULT now()
);

-- Append-only. No UPDATE, no DELETE grant for the application role.
CREATE TABLE audit_logs (
  id                 bigserial PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  request_id         uuid NOT NULL,
  user_id            uuid,
  event_type         text NOT NULL,             -- QUERY|ACCESS_DENIED|RULE_CHANGE|LOGIN
  event_action       text NOT NULL,
  resource_type      text,
  resource_id        text,
  decision           text,                      -- ALLOW | DENY
  decision_reason    text,
  payload            jsonb NOT NULL DEFAULT '{}',
  prev_hash          bytea,
  entry_hash         bytea NOT NULL,            -- hash chain, tamper evidence
  occurred_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX ON audit_logs (tenant_id, event_type, occurred_at DESC);

CREATE TABLE feedback (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL,
  query_id           uuid NOT NULL REFERENCES queries(id),
  user_id            uuid NOT NULL REFERENCES users(id),
  verdict            text NOT NULL,             -- CORRECT | INCORRECT
  reason_code        text,                      -- WRONG_SOURCE | WRONG_RULE | ...
  comment            text,
  created_at         timestamptz NOT NULL DEFAULT now()
);
```

## 7. Notes

- **Money as integers.** `cost_micros` is `bigint`. Never float.
- **Answer retention.** `query_results.answer` may contain confidential
  content; it inherits the tenant's retention policy and is a candidate for
  encryption at rest with a tenant-scoped key in V2.
- **Hash chaining.** `entry_hash = H(prev_hash || canonical_json(entry))` gives
  tamper evidence without an external ledger. Cheap, and it is exactly what an
  enterprise security reviewer asks about.
- **`queries.raw_query` is user input.** It is stored, never interpolated into
  a prompt without the untrusted-data envelope described in the security doc.
