# 00 — Final Architecture

## 1. What this system actually is

An **orchestration and governance layer** that sits between employees and
enterprise data. It is not a model, not a chatbot, and not a vector database.
Its job is to answer, for every incoming query:

- What is the cheapest execution plan that answers this reliably?
- What is this user allowed to see while doing it?
- Can I prove the answer afterwards?

Everything else in the platform exists to serve one of those three questions.

## 2. Design stance (and where it differs from the brief)

Five positions worth stating up front, because they shape every diagram below.

**2.1 The router emits a budget, not a label.**
Pre-execution complexity scoring is fundamentally partially-observable: you
cannot know how many reasoning hops a question needs before you have looked at
the data. A single `COMPLEX/SIMPLE` label bakes in a guess. Instead the router
emits an **execution plan with an explicit budget** — max tool calls, max
tokens, max latency, verification requirement — and the orchestrator escalates
inside that budget when evidence is insufficient. Router accuracy stops being a
single point of failure and becomes a cost optimization.

**2.2 Escalation is a first-class control loop, not a fallback.**
Under-routing (complex query answered on the fast path) is the failure mode
that produces confidently wrong answers. Over-routing merely costs money. So
every path terminates in a **sufficiency check** that can escalate, and the
evaluation harness weights missed escalation far more heavily than false
escalation.

**2.3 Cost is the wedge; governance is the moat.**
Inference prices fall fast. A product whose only claim is "we spend fewer
tokens" has a depreciating differentiator. The durable asset is the record:
permission-aware retrieval, deterministic policy enforcement, verified answers,
and a complete audit trail — "answers you can defend to an auditor." Lead with
cost savings in the sales motion; build the moat in governance.

**2.4 "Neuro-symbolic" is internal vocabulary, never customer vocabulary.**
The symbolic engine's near-term commercial value is **deterministic policy
enforcement** (return eligibility, approval thresholds, data-access rules), not
causal inference. Build it, benchmark it, and sell it as a policy engine.

**2.5 Neural/symbolic conflict detection is only meaningful on typed claims.**
Comparing free-form prose against a rule engine produces noise. Conflict
detection is scoped to cases where both sides emit the *same typed predicate*
(e.g. `return_eligible: boolean`). Outside that, the symbolic result is simply
authoritative and the neural output is constrained to phrasing it.

## 3. Layered architecture

```text
┌─────────────────────────────────────────────────────────────┐
│  L6  CLIENTS        React SPA · Admin console · REST API    │
├─────────────────────────────────────────────────────────────┤
│  L5  EDGE           AuthN (JWT/OIDC) · Rate limit · Tenant  │
│                     resolution · Request ID · Input guard   │
├─────────────────────────────────────────────────────────────┤
│  L4  PLANNING       Preprocess → Intent · Complexity · Risk │
│                     → Router → ExecutionPlan (+budget)      │
├─────────────────────────────────────────────────────────────┤
│  L3  ORCHESTRATION  Plan executor · Step budget accounting  │
│                     Sufficiency check · Escalation ladder   │
├─────────────────────────────────────────────────────────────┤
│  L2  EXECUTION      Fast path │ RAG path │ SQL path │       │
│                     Neuro-symbolic path │ Security gate     │
├─────────────────────────────────────────────────────────────┤
│  L1  CAPABILITY     Retrieval · SQL exec · Rule engine ·    │
│                     Inference · Verification · Explanation ·│
│                     Confidence · Model gateway              │
├─────────────────────────────────────────────────────────────┤
│  L0  FOUNDATION     PostgreSQL + pgvector · Policy Decision │
│                     Point · Audit sink · Cost ledger        │
└─────────────────────────────────────────────────────────────┘

Cross-cutting (every layer): Authorization · Tenant isolation · Audit ·
Telemetry · Cost accounting
```

The critical structural rule: **L1 capability services never make authorization
decisions themselves.** They call the Policy Decision Point (L0) and receive a
constraint set (allowed tables, allowed columns, row predicates, allowed
document classifications). The constraint set is applied *inside the query* —
as a SQL `WHERE` clause and a vector-search metadata filter — so unauthorized
data is never materialized, let alone sent to a model.

## 4. Request path

```text
HTTP request
   │
   ├─ [L5] Authenticate → Principal{user_id, tenant_id, roles, permissions}
   ├─ [L5] Rate limit (per user, per tenant, per cost budget)
   ├─ [L5] Input guard (size, encoding, structural injection markers)
   │
   ├─ [L4] Preprocess: normalize, PII-tag, resolve conversation refs
   ├─ [L4] Analyze (parallel):
   │        ├── IntentClassifier   → intent + entities + data hints
   │        ├── ComplexityEngine   → complexity_score + features
   │        └── RiskEngine         → risk_score + risk_factors
   ├─ [L4] Router: (intent, complexity, risk, permissions, cost policy)
   │        → ExecutionPlan { route, steps, sources, budget, verification,
   │                          model_tier, security_level }
   │
   ├─ [L3] Orchestrator executes plan step by step
   │        └── after each step: SufficiencyCheck
   │                 ├─ sufficient  → continue
   │                 └─ insufficient → escalate (if budget remains)
   │
   ├─ [L2] Route execution (see docs/architecture/05-query-lifecycles.md)
   │
   ├─ [L1] Verification (mandatory when plan.verification_required)
   ├─ [L1] Explanation assembly (evidence + rules + summary)
   ├─ [L1] Confidence estimation (system_confidence, not model logprob)
   │
   ├─ [L5] Output filter: redaction, citation integrity, leak check
   └─ [L0] Audit write (synchronous for security decisions)
```

## 5. Component catalogue

Ownership boundaries. Each is a Spring module (a package with an explicit
public interface), not a microservice.

### Foundation (F)
| ID | Component | Responsibility |
| --- | --- | --- |
| F1 | `platform-core` | Request context, principal, tenant context propagation, error model |
| F2 | `persistence` | Datasource config, Flyway migrations, tenant-scoped repositories |
| F3 | `audit` | Append-only decision log, hash-chained, queryable |
| F4 | `telemetry` | Metrics, tracing, latency histograms |
| F5 | `cost-ledger` | Token and cost accounting per request/tenant/route |

### Identity & policy (I)
| ID | Component | Responsibility |
| --- | --- | --- |
| I1 | `auth` | Login, JWT issue/verify, OIDC federation (V2), sessions |
| I2 | `tenancy` | Tenant resolution, isolation enforcement, RLS session vars |
| I3 | `rbac` | Roles, permissions, user-role assignment |
| I4 | `pdp` | **Policy Decision Point** — the single authority. Input: principal + resource request. Output: `AccessConstraint` (allow/deny + row predicates + column mask + doc classification filter) |

### Knowledge (K)
| ID | Component | Responsibility |
| --- | --- | --- |
| K1 | `ingestion` | Upload, Tika extraction, cleaning, chunking, metadata, ACL tagging |
| K2 | `embedding` | Embedding provider abstraction, batching, caching |
| K3 | `retrieval` | Permission-filtered vector + lexical hybrid search, reranking |
| K4 | `datasource` | Registered SQL sources, schema catalogue, column classification |
| K5 | `factstore` | Typed facts with provenance (entity, predicate, value, source, confidence, valid_time) |

### Reasoning (R)
| ID | Component | Responsibility |
| --- | --- | --- |
| R1 | `model-gateway` | Provider-agnostic LLM interface, tiering, retries, token metering |
| R2 | `intent` | Intent classification |
| R3 | `complexity` | Complexity feature extraction and scoring |
| R4 | `risk` | Risk scoring and injection detection |
| R5 | `router` | ExecutionPlan generation |
| R6 | `orchestrator` | Plan execution, budget accounting, escalation ladder |
| R7 | `sqlpath` | NL→SQL generation, AST validation, constrained execution |
| R8 | `rules` | Rule storage, rule compilation, deterministic evaluation |
| R9 | `inference` | Forward-chaining engine over facts + rules, with trace |
| R10 | `fusion` | Neural/symbolic reconciliation on typed predicates, conflict objects |
| R11 | `verification` | Source, fact, rule, consistency, permission, contradiction checks |
| R12 | `explanation` | Structured explanation assembly (never raw chain-of-thought) |
| R13 | `confidence` | `system_confidence` from multi-signal aggregation |

### Application (A)
| ID | Component | Responsibility |
| --- | --- | --- |
| A1 | `query-api` | `/api/v1/query` and streaming variant |
| A2 | `admin-api` | Rules, data sources, users, documents, policies |
| A3 | `analytics-api` | Cost, routing distribution, security events |
| A4 | `feedback` | User feedback capture → evaluation dataset |
| A5 | `web` | React + TypeScript SPA |

### Evaluation (E) — Python
| ID | Component | Responsibility |
| --- | --- | --- |
| E1 | `benchmark` | Query corpus, expected route, expected answer, expected sources |
| E2 | `harness` | Runs Systems A/B/C/D against the corpus via the public API |
| E3 | `metrics` | Accuracy, routing F1, escalation rates, cost, latency, calibration |
| E4 | `redteam` | Adversarial security corpus and scoring |

## 6. Dependency graph

Build order is derived from this. An arrow means "must exist first."

```text
F1 platform-core
 ├─> F2 persistence ──> F3 audit ──> F5 cost-ledger
 │        │
 │        ├─> I1 auth ──> I3 rbac ──┐
 │        └─> I2 tenancy ───────────┴─> I4 pdp  ◄── SECURITY KEYSTONE
 │                                        │
 │                                        ├─────────────────┐
 │                                        │                 │
 │        ┌───────────────────────────────┘                 │
 │        │                                                 │
 │   K1 ingestion ─> K2 embedding ─> K3 retrieval           │
 │   K4 datasource ────────────────────────────> R7 sqlpath ┘
 │
 └─> R1 model-gateway
          ├─> R2 intent ─┐
          ├─> R3 complexity ─┤
          └─> R4 risk ───────┴──> R5 router ──> R6 orchestrator
                                                     │
                                    ┌────────────────┼──────────────┐
                                    │                │              │
                              K3 retrieval      R7 sqlpath    R8 rules
                                                                    │
                                                              R9 inference
                                                                    │
                                                    K5 factstore ────┤
                                                                    │
                                                              R10 fusion
                                                                    │
                                                            R11 verification
                                                                    │
                                        R12 explanation ◄───────────┤
                                        R13 confidence ◄────────────┘
                                                    │
                                              A1 query-api ──> A5 web
                                                    │
                                              A4 feedback ──> E1 benchmark
                                                                  │
                                                            E2 harness ──> E3 metrics
                                                                            E4 redteam
```

### Hard ordering constraints

1. **I4 `pdp` before K3 `retrieval` and R7 `sqlpath`.** Retrieval built without
   the PDP will grow an ad-hoc filter that becomes the permanent security hole.
   This is the single most important sequencing rule in the project.
2. **R8 `rules` + R9 `inference` are LLM-independent and can be built in
   parallel by a second developer from day one.** They have no dependency on
   any AI component. This is the natural parallelization seam for a small team.
3. **F3 `audit` before any route executes.** Retrofitting audit means
   backfilling a decision record that no longer exists.
4. **E1 `benchmark` before R5 `router` is tuned.** Thresholds without a
   benchmark are numerology.
5. **R10 `fusion` last among reasoning components** — it is meaningless until
   both sides produce typed predicates.

## 7. Where the LLM is and is not allowed

| Decision | Made by | LLM involvement |
| --- | --- | --- |
| Is this user authenticated? | I1 | none |
| May this user see this row/document/column? | I4 PDP | none |
| Is this SQL safe to run? | R7 validator (AST) | none |
| Is this business rule satisfied? | R8/R9 | none |
| Which route should this take? | R5 | advisory signal only, bounded by policy |
| What does this sentence mean? | R2/neural | yes |
| What is the final prose? | R1 | yes, over pre-authorized evidence only |

Any future feature request that moves a row from "none" to "yes" is a security
change and requires explicit review.
