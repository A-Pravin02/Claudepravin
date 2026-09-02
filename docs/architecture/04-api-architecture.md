# 04 — API Architecture

## 1. Conventions

- Base path `/api/v1`. Versioned from the first commit.
- Bearer JWT on every endpoint except `/auth/login`, `/auth/refresh`, `/health`.
- Tenant is derived **from the token, never from a request parameter.** A
  `tenant_id` in a request body is ignored; supplying one that differs from the
  token's is logged as a security event.
- Every response carries `X-Request-Id`, matching `audit_logs.request_id`.
- Errors are RFC 9457 `application/problem+json`.
- **Authorization failures return the same shape and timing as "no results."**
  Distinguishing "denied" from "absent" leaks the existence of records. The
  audit log records the true reason; the caller does not see it.

## 2. Public surface

### Query
```
POST   /api/v1/query                 Submit a query, receive a full result
GET    /api/v1/query/{id}            Retrieve a past result (own queries, or
                                     any within tenant with VIEW_AUDIT)
GET    /api/v1/query/{id}/trace      Reasoning trace (requires VIEW_TRACE)
POST   /api/v1/query/{id}/feedback   Submit feedback
```

### Documents
```
POST   /api/v1/documents             Upload (multipart) → async ingestion job
GET    /api/v1/documents             List (permission-filtered)
GET    /api/v1/documents/{id}        Metadata
DELETE /api/v1/documents/{id}        Delete + cascade chunk/vector removal
GET    /api/v1/documents/{id}/status Ingestion status
```

### Admin
```
GET|POST|PUT   /api/v1/admin/rules[/{id}]
POST           /api/v1/admin/rules/{id}/test    Dry-run against sample facts
GET|POST       /api/v1/admin/data-sources
PUT            /api/v1/admin/data-sources/{id}/tables/{table}  Allowlist toggle
GET|POST|PUT   /api/v1/admin/users[/{id}]
GET|POST       /api/v1/admin/roles
GET            /api/v1/admin/audit                Filterable audit browser
```

### Analytics
```
GET    /api/v1/analytics/routes      Route distribution over a window
GET    /api/v1/analytics/cost        Cost per query, per route, per user
GET    /api/v1/analytics/security    Denials, injection detections, anomalies
GET    /api/v1/analytics/quality     Feedback rates, escalation rates
```

## 3. The core contract

`POST /api/v1/query`

```json
{
  "query": "Did inventory shortages cause the sales decline in Chennai?",
  "conversation_id": "…optional…",
  "options": { "max_cost_micros": 50000, "explain": true }
}
```

Response:

```json
{
  "request_id": "1f9e…",
  "query_id": "8c2a…",
  "status": "ANSWERED",
  "answer": "Sales declined primarily during periods of low inventory.",
  "routing": {
    "intent": "CAUSAL_REASONING",
    "complexity_score": 0.84,
    "risk_score": 0.18,
    "planned_route": "NEURO_SYMBOLIC",
    "final_route": "NEURO_SYMBOLIC",
    "escalations": 0
  },
  "explanation": {
    "evidence": [
      {"type": "SQL",      "source": "sales_db.sales",     "summary": "…"},
      {"type": "SQL",      "source": "sales_db.inventory", "summary": "…"},
      {"type": "DOCUMENT", "source": "inventory_policy.pdf",
       "document_id": "…", "page": 4, "quote": "…"}
    ],
    "rules_applied": [
      {"rule_key": "R003", "version": 1, "name": "Inventory threshold",
       "outcome": "inventory_status = LOW"}
    ],
    "reasoning_summary": "Inventory was below the configured safety stock on 11 of 90 days; those days account for 62% of the observed sales shortfall.",
    "conflicts": []
  },
  "confidence": {
    "system_confidence": 0.91,
    "calibrated": false,
    "signals": {
      "retrieval_quality": 0.88, "source_coverage": 0.95,
      "rule_consistency": 1.0,  "verification": 1.0, "neural_agreement": 0.82
    }
  },
  "verification": {
    "performed": true,
    "checks": {"citation_grounding": "PASS", "rule_consistency": "PASS",
               "permission_recheck": "PASS", "contradiction": "PASS"}
  },
  "cost": {"input_tokens": 1620, "output_tokens": 220,
           "embedding_tokens": 18, "cost_micros": 4310},
  "latency_ms": 2810
}
```

A denial:

```json
{
  "request_id": "…", "query_id": "…",
  "status": "DENIED",
  "answer": "This request requires permissions your account does not have. Contact your administrator if you believe this is an error.",
  "routing": {"intent": "SECURITY_REQUEST", "risk_score": 0.94,
              "final_route": "SECURITY_GATE"},
  "explanation": {"policy": "Salary data is restricted to the HR role.",
                  "required_permission": "READ_SALARY"},
  "confidence": null
}
```

Note what a denial does **not** contain: no row count, no confirmation that the
data exists, no partial results, no model output at all. The security gate
terminates the request before any model sees the data.

## 4. Internal contracts

These are the interfaces that define the system; the HTTP surface is a thin
shell over them.

```java
// I4 — the only authority on access.
public interface PolicyDecisionPoint {
    AccessConstraint evaluate(Principal p, ResourceRequest r);
}

public record AccessConstraint(
    Decision decision,                       // ALLOW | DENY | ALLOW_FILTERED
    Set<String> allowedTables,
    Map<String, Set<String>> allowedColumns, // table -> columns
    List<RowPredicate> rowPredicates,        // compiled into SQL and filters
    Set<String> allowedClassifications,      // for retrieval
    String denyReason                        // audit only, never returned raw
) {}

// R5 — planning output. The router's real product.
public record ExecutionPlan(
    Route route,                             // FAST|RAG|SQL|NEURO_SYMBOLIC|SECURITY_GATE
    List<PlanStep> steps,
    Set<String> sources,
    Budget budget,                           // maxSteps, maxTokens, maxLatencyMs, maxCostMicros
    boolean symbolicReasoning,
    boolean verificationRequired,
    ModelTier modelTier,
    SecurityLevel securityLevel,
    String rationale
) {}

// R6 — the escalation control loop.
public interface SufficiencyCheck {
    Verdict assess(ExecutionPlan plan, StepResult result);
    // SUFFICIENT | ESCALATE(reason) | INSUFFICIENT_STOP(reason)
}

// R8/R9 — deterministic, no LLM anywhere in this signature.
public interface RuleEngine {
    InferenceResult infer(List<Fact> facts, List<Rule> rules, InferenceConfig cfg);
}
public record InferenceResult(
    List<Fact> conclusions,
    List<RuleFiring> rulesFired,
    List<Conflict> conflicts,
    int iterations,
    boolean fixpointReached
) {}

// R1 — provider abstraction.
public interface ModelProvider {
    String id();
    CompletionResult complete(CompletionRequest req);  // req carries tier + budget
    boolean supports(ModelTier tier);
}
```

The `RuleEngine` signature is worth staring at: it takes facts and rules and
returns conclusions. It has no dependency on retrieval, models, tenants, or
HTTP. That is what makes it unit-testable to exhaustion and what makes the
"deterministic policy" claim defensible to a customer's compliance team.
