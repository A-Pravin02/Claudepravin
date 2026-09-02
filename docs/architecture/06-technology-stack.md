# 06 — Technology Stack

Choices, with the alternative that was rejected and why.

| Layer | Choice | Alternative considered | Why |
| --- | --- | --- | --- |
| Backend | Java 21 + Spring Boot 3.x | Node/NestJS, Python/FastAPI | Spring Security is the strongest off-the-shelf authz framework; enterprise buyers' security reviews go easier; virtual threads make IO-bound LLM fan-out cheap. Java 21 specifically for virtual threads and pattern matching in the rule evaluator. |
| Auth | Spring Security + JWT (MVP), OIDC (V2) | Keycloak from day one | Keycloak is the right V2 answer but adds a service and an admin surface before there are users. JWT with short-lived access + rotating refresh tokens is sufficient and replaceable. |
| Database | PostgreSQL 16 | — | RLS is the deciding feature. It converts tenant isolation from a code-review problem into an engine guarantee. |
| Vector | pgvector (HNSW) | Qdrant, Weaviate, Pinecone | One datastore means the permission filter and the vector search are the same transaction. A separate vector DB forces you to replicate ACLs into it — the most common source of enterprise RAG leaks. Revisit past ~10M chunks/tenant. |
| Migrations | Flyway | Liquibase | Plain SQL; reviewable by a DBA. |
| SQL validation | JSqlParser | regex, string matching | **Security-critical.** Validation must be on a parsed AST. Regex-based SQL "validation" is defeated by comments, unicode, and nested constructs. If it does not parse, it does not run. |
| Document parsing | Apache Tika | per-format libraries | One dependency, broad coverage. Run it out-of-process or sandboxed in V2: Tika parses hostile files and has a CVE history. |
| Embeddings | Provider API behind `EmbeddingProvider` | local model | Start hosted; the interface exists so a regulated tenant can be served by a local model without a rewrite. |
| LLM | Provider API behind `ModelProvider` | direct SDK use | Provider-agnostic from commit one; tiering (cheap/standard/reasoning) is a routing input in V2. |
| Frontend | React 18 + TypeScript + Vite + TanStack Query | Next.js | No SSR requirement; an authenticated internal SPA. Next.js adds a server to secure for no benefit here. |
| API contract | OpenAPI generated from controllers → TS client | hand-written types | Drift between backend and frontend types is a recurring bug class; generate it. |
| Container | Docker + Compose (dev/demo) | Kubernetes | Compose until there is a scaling reason. |
| CI | GitHub Actions | — | Build, test, ArchUnit, security suite, Flyway validation on every PR. |
| Metrics | Micrometer → Prometheus/Grafana (V2) | — | Micrometer instrumentation from the MVP; the scrape target comes later. |
| Cache | Redis (V2) | — | The semantic cache on the fast path is likely the single largest cost lever; deliberately deferred so the benchmark measures routing, not caching. |
| Python | 3.12, uv, pytest | — | Evaluation harness, benchmarking, calibration analysis only. Not in the request path. |

## On the second service question

There is no Python AI service in the MVP. The Java process calls model and
embedding APIs directly. A Python service is introduced only when one of these
becomes true:

1. A model must run in-process (local embeddings, a fine-tuned reranker).
2. The router becomes a trained model rather than a scoring function.

Until then, a second service buys latency and deployment complexity and nothing
else.
