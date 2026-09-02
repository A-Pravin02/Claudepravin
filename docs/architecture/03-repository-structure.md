# 03 — Repository Structure

Single repository (monorepo). Rationale: one team, tightly coupled contracts,
atomic cross-cutting changes (a schema change touches migration + Java + TS
types in one commit). Split only when release cadences genuinely diverge.

```text
adaptive-enterprise-ai/
├── README.md
├── docker-compose.yml
├── Makefile
│
├── apps/
│   ├── api/                              # Spring Boot modular monolith
│   │   ├── pom.xml
│   │   └── src/main/java/com/aea/
│   │       ├── AeaApplication.java
│   │       ├── platform/                 # F1 request ctx, principal, errors
│   │       ├── persistence/              # F2 tenant datasource, RLS session
│   │       ├── audit/                    # F3
│   │       ├── telemetry/                # F4
│   │       ├── cost/                     # F5 cost ledger
│   │       ├── auth/                     # I1
│   │       ├── tenancy/                  # I2
│   │       ├── rbac/                     # I3
│   │       ├── pdp/                      # I4  ◄ security keystone
│   │       ├── knowledge/
│   │       │   ├── ingestion/            # K1
│   │       │   ├── embedding/            # K2
│   │       │   ├── retrieval/            # K3
│   │       │   ├── datasource/           # K4
│   │       │   └── facts/                # K5
│   │       ├── reasoning/
│   │       │   ├── model/                # R1 model gateway
│   │       │   ├── intent/               # R2
│   │       │   ├── complexity/           # R3
│   │       │   ├── risk/                 # R4
│   │       │   ├── router/               # R5
│   │       │   ├── orchestrator/         # R6
│   │       │   ├── sql/                  # R7
│   │       │   ├── rules/                # R8  ◄ zero AI dependency
│   │       │   ├── inference/            # R9  ◄ zero AI dependency
│   │       │   ├── fusion/               # R10
│   │       │   ├── verification/         # R11
│   │       │   ├── explanation/          # R12
│   │       │   └── confidence/           # R13
│   │       └── api/
│   │           ├── query/                # A1
│   │           ├── admin/                # A2
│   │           ├── analytics/            # A3
│   │           └── feedback/             # A4
│   │   └── src/main/resources/
│   │       ├── application.yml
│   │       ├── db/migration/             # Flyway V001__...sql
│   │       └── prompts/                  # versioned prompt templates
│   │
│   └── web/                              # A5 React + TypeScript + Vite
│       ├── package.json
│       └── src/
│           ├── api/                      # generated client from OpenAPI
│           ├── features/{auth,chat,admin,analytics}/
│           └── components/
│
├── ai/                                   # Python: experiments and evaluation
│   ├── pyproject.toml
│   ├── evaluation/                       # E2 harness, E3 metrics
│   ├── benchmark/                        # E1 corpus (versioned JSONL)
│   ├── redteam/                          # E4 adversarial corpus
│   └── notebooks/
│
├── data/
│   ├── demo/techstore/                   # seed SQL, policy PDFs, rules JSON
│   └── fixtures/
│
├── rules/                                # shipped rule packs (JSON)
│   └── techstore/{returns,inventory,hr,discount}.json
│
├── docs/
│   ├── architecture/
│   ├── security/
│   └── research/
│
├── tests/                                # cross-cutting suites
│   ├── routing/  rag/  reasoning/  security/  tenancy/
│
└── .github/workflows/ci.yml
```

## Module discipline

Java packages, not Maven modules, in the MVP — Maven multi-module adds build
friction before the boundaries have stabilised. Discipline is enforced by:

1. **ArchUnit tests in CI.** The rules that matter:
   - Nothing outside `pdp` may construct an `AccessConstraint`.
   - `retrieval` and `sql` must depend on `pdp`.
   - `rules` and `inference` must not depend on `reasoning.model` (proves the
     symbolic engine is genuinely LLM-independent).
   - `api` must not depend on `persistence` directly.
2. **Each package exposes one interface** and keeps implementations
   package-private.

When a package needs independent scaling, it is already a clean seam and can be
extracted. That is the "modular monolith" bet: pay the discipline cost now,
keep the option, skip the distributed-systems tax.
