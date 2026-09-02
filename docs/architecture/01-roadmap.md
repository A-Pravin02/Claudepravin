# 01 — MVP Scope and Roadmap

## 1. MVP definition

The MVP is the **thinnest end-to-end system that can demonstrate the core
hypothesis under measurement**. That means it must contain, at minimum: two
distinguishable execution paths, a real authorization boundary, a deterministic
rule engine, and a cost ledger. Anything that does not serve the hypothesis or
the security boundary is out.

### In scope

| Area | MVP contents |
| --- | --- |
| Tenancy | `tenant_id` on every row, PostgreSQL RLS enforced, one demo tenant (TechStore) |
| Auth | Email/password login, JWT access tokens, refresh tokens, 5 seeded roles |
| Authorization | PDP returning `AccessConstraint`; enforced in retrieval filter and SQL predicate |
| Documents | Upload PDF/DOCX/TXT, Tika extraction, chunking, embedding, pgvector storage with ACL metadata |
| Retrieval | Permission-filtered hybrid search (vector + `tsvector`), no cross-encoder rerank yet |
| SQL path | NL→SQL over registered read-only source, JSqlParser AST validation, SELECT-only, allowlisted tables/columns, injected tenant predicate, row limit, statement timeout |
| Rules | JSON-defined rules in Postgres, forward-chaining engine, full firing trace, LLM-independent |
| Router | Feature-based complexity + risk scoring, one LLM classifier call for intent, deterministic routing matrix, emits ExecutionPlan with budget |
| Routes | FAST, RAG, SQL, NEURO_SYMBOLIC, SECURITY_GATE |
| Escalation | Sufficiency check after each path, one escalation level permitted |
| Verification | Citation grounding + rule-consistency + permission recheck (the three that catch real failures) |
| Explanation | Structured evidence/rules/summary object |
| Confidence | `system_confidence` from 5 signals, uncalibrated and labelled as such |
| Audit | Full decision record per request, append-only |
| Cost | Token and cost ledger per request, route distribution dashboard |
| UI | Login, chat, answer with sources, route badge, confidence, feedback buttons |
| Eval | ~150 seeded benchmark queries (not 700) across 7 categories, harness comparing Systems A–D |

### Explicitly out of MVP

Knowledge graph. Backward chaining. Probabilistic/temporal reasoning. Learned
router. Tool/action execution (write operations of any kind). Human approval
workflows. OIDC/SAML. Redis. Kubernetes. Microservices. Multi-model routing.
Streaming responses. Cross-encoder reranking. Admin rule editor UI (rules are
seeded via JSON files + API in MVP).

### MVP exit criteria

The MVP is done when all of these are true, measured by the harness:

1. All five demo queries (§49 of the brief) route correctly and end-to-end.
2. `EMPLOYEE` role cannot retrieve a salary document or a salary column, proven
   by an automated test, at both the retrieval and SQL layers.
3. Cross-tenant leakage test suite passes with zero leaks.
4. Prompt-injection corpus (≥30 cases) produces zero instruction-following.
5. Route distribution and cost per query are reported for Systems A/B/C/D on
   the benchmark, with confidence intervals.
6. Missed-escalation rate is reported and is the headline routing metric.

## 2. Phase plan

Phases map to the brief's §38 but are re-ordered where dependencies demand it.
Notably, **tenancy and the PDP move before RAG**, because retrieval built
without them cannot be secured afterwards.

### V0 — Foundation (Milestone 1)
Repo, Docker Compose (Postgres+pgvector), Spring Boot skeleton, Flyway, React
shell, JWT auth, RBAC tables, tenant context + RLS, audit skeleton, health
endpoints, CI. **Detailed in `07-milestone-1.md`.**

### V0.5 — Model gateway and chat
`ModelProvider` interface, one implementation, token metering, cost ledger,
`POST /api/v1/query` returning a plain LLM answer with a full audit record.
Establishes the request envelope every later phase fills in.

### V0.75 — PDP
`AccessConstraint`, permission catalogue, document classification, column
classification. No AI. Tested exhaustively. **Gate: nothing that touches
enterprise data ships before this.**

### V1 — The measurable product
1. Ingestion + embedding + pgvector.
2. Permission-filtered retrieval, RAG path with citations.
3. SQL path with AST validation.
4. Rule engine + forward chaining (parallel track, no AI dependency).
5. Complexity/risk/intent engines, router, ExecutionPlan.
6. Orchestrator, sufficiency check, one-level escalation.
7. Neuro-symbolic path: retrieval → typed fact extraction → inference → fusion.
8. Verification, explanation, confidence.
9. Security hardening pass + red-team corpus.
10. Benchmark + A/B/C/D comparison.

**V1 ships when the hypothesis is either supported or refuted with evidence.**
A refuted hypothesis is a valid V1 outcome and changes the product, not the
schedule.

### V2 — Enterprise readiness
OIDC/SAML SSO and SCIM. Admin console: rule editor, data source connection UI,
audit browser, cost dashboard. Cross-encoder reranking. Redis caching (semantic
cache on the fast path is the largest single cost lever). Prometheus/Grafana.
Multi-model tier routing (cheap/standard/reasoning). Read-only connectors
beyond Postgres (MySQL, Snowflake, BigQuery). Human-in-the-loop approval for
high-risk queries. Rate limiting by cost budget, not request count.

### V3 — Platform
Tool/action execution behind the policy engine with approval workflows.
Knowledge graph for multi-hop entity traversal, once benchmark evidence shows
the flat fact store is the bottleneck. Learned router trained on accumulated
`(query, plan, outcome, cost)` tuples — this is the point at which the
accumulated audit log becomes a data moat. Backward chaining and constraint
solving. Private-cloud and on-prem deployment packaging. Local/approved model
support for regulated tenants. Calibration of `system_confidence` into a real
probability.

## 3. Team shape for a small team

Three parallel tracks, minimum viable staffing of 2–3 people:

- **Track A (backend/security):** F, I, A modules. Owns the PDP.
- **Track B (reasoning):** R8/R9 rule engine and inference first — zero
  dependency on Track A. Then R2–R6, R10–R13.
- **Track C (data/eval):** K1–K3 ingestion and retrieval, then E1–E4 in Python.

Track B starting on the rule engine is what keeps a 2-person team from
serializing behind auth.
