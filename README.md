# Adaptive Enterprise AI

A secure adaptive AI reasoning layer for enterprise data.

Connect your company's databases, documents, policies, and business rules.
The platform chooses the cheapest reliable execution path for each query,
enforces permissions at every boundary, and verifies complex answers before
they reach employees.

## Status

Pre-implementation. Architecture and roadmap only — see `docs/architecture/`.

| Document | Contents |
| --- | --- |
| [00-overview.md](docs/architecture/00-overview.md) | Final architecture, component list, dependency graph |
| [01-roadmap.md](docs/architecture/01-roadmap.md) | MVP scope, V1/V2/V3 roadmap |
| [02-database-schema.md](docs/architecture/02-database-schema.md) | PostgreSQL schema |
| [03-repository-structure.md](docs/architecture/03-repository-structure.md) | Repo and module layout |
| [04-api-architecture.md](docs/architecture/04-api-architecture.md) | HTTP API and internal contracts |
| [05-query-lifecycles.md](docs/architecture/05-query-lifecycles.md) | End-to-end trace for each route |
| [06-technology-stack.md](docs/architecture/06-technology-stack.md) | Stack choices and rejected alternatives |
| [07-milestone-1.md](docs/architecture/07-milestone-1.md) | First implementation milestone |
| [security-architecture.md](docs/security/security-architecture.md) | Trust domains, controls, threat model |
| [open-questions.md](docs/research/open-questions.md) | Unproven claims and how we test them |

## Non-negotiables

1. Authorization is enforced in the data layer, not by the model.
2. Retrieved content is data, never instruction.
3. No LLM-generated SQL executes unvalidated.
4. Every routing and security decision is auditable.
5. No performance or cost claim without benchmark evidence.
