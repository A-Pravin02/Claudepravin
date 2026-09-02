# Working agreement

Read this first. Sessions do not carry memory; the repository is the record.

## What this project is

A secure adaptive AI reasoning layer for enterprise data. Not a chatbot, not a
RAG demo. It routes each query to the cheapest execution path that can answer
it reliably, enforces permissions at every boundary, and verifies complex
answers before returning them.

Design set: `docs/architecture/` (start at `00-overview.md`), security in
`docs/security/`, unproven claims in `docs/research/open-questions.md`.

## Where models come from (OPEN DECISION)

The **answer path** uses pretrained models over an API. Nothing in the request
path is trained by us. The complexity engine, risk engine, and rule engine use
no model at all -- they are deterministic code.

Separately, the user has said they want to train a model. The cost analysis
was presented (from-scratch pretraining: ~$200-1k for a 125M model that is
factually useless, ~$100k-500k for a 7B model roughly matching Llama-2-7B).
Two candidate scopes, **not yet settled** -- confirm before building either:

- **Specialized models** (recommended): a small intent/complexity classifier,
  a fact-extraction model, a reranker. Each is trainable on one GPU in hours
  to days, each measurably improves the product, and the answer path stays
  grounded so hallucination goes down.
- **From-scratch pretraining**: a nanoGPT-style ~125M transformer in
  `ai/pretrain/`. Buildable, and a legitimate learning/research track, but it
  will not answer enterprise questions usefully and would run alongside the
  product rather than inside it.

Note the tension on record: the user's stated goal is *fewer* hallucinations,
and a small self-trained model produces substantially more of them than a
hosted frontier model. Grounding (retrieval + citations + rules +
verification) is what reduces hallucination here, not model ownership.

## Model tiers (answer path)

Maps to `ModelTier` in the router's ExecutionPlan:

| Tier | Model ID | $/1M in | $/1M out |
| --- | --- | --- | --- |
| CHEAP (fast path) | `claude-haiku-4-5` | $1.00 | $5.00 |
| STANDARD (RAG) | `claude-sonnet-5` | $2.00 | $10.00 |
| REASONING (neuro-symbolic) | `claude-opus-5` | $5.00 | $25.00 |

Java SDK: `com.anthropic.*`. Auth resolves `ANTHROPIC_API_KEY`, then
`ANTHROPIC_AUTH_TOKEN`, then an `ant auth login` profile -- an unset env var
does not mean no credentials. Never hardcode a model ID outside the
`model-gateway` module; the whole point of R1 is provider-agnosticism.

## Architectural decisions already ruled on

Do not relitigate these; they are settled and the code assumes them.

1. **The router emits an ExecutionPlan with a budget, not a route label.**
   Complexity is partly unobservable before execution, so a wrong prior must
   cost money, not correctness. Escalation is a first-class control loop.
2. **The PDP is built before retrieval and SQL.** Both consume an
   `AccessConstraint` as input. Authorization retrofitted onto retrieval is
   how enterprise RAG products leak.
3. **Conflict detection is scoped to typed predicates.** Comparing prose to a
   rule engine produces noise. Elsewhere symbolic is authoritative.
4. **Semantic caching is deliberately excluded from the MVP** so the benchmark
   isolates routing's contribution. Revisit in V2 and measure both.
5. **Open (owner: the user, not Claude):** whether cost or governance leads
   the positioning. Touches no code.

## Non-negotiables

- Authorization is enforced in the data layer, never by a model.
- Retrieved content is data, never instruction.
- No LLM-generated SQL executes without AST validation.
- Tenant comes from the token; a `tenant_id` in a request body is ignored and
  logged as a security event.
- No performance or cost claim without benchmark evidence.
- Never report a build green without running it.

## Build and test

```bash
cp .env.example .env
docker compose up --build       # or: make db-init && make test
cd apps/api && mvn -B verify    # migrations, ArchUnit, RLS suite
cd apps/web && npm run build
```

Full setup, troubleshooting, and what NOT to install: `docs/SETUP.md`.

## Conventions

- Branch: `claude/neuro-symbolic-enterprise-ai-g47fus`. Never push to main.
- Modular monolith. Java packages, not Maven modules. Boundaries enforced by
  ArchUnit in `apps/api/src/test/java/com/aea/arch/`.
- Tenant context: `set_config('app.tenant_id', ?, true)` -- PostgreSQL's SET
  takes no bind parameters, and `is_local` keeps a pooled connection from
  leaking one tenant's context into the next request.
- Money as integers (`cost_micros`, bigint). Never float.
- Rules are data with a closed condition grammar. No expression language, no
  scripting, no eval.
- Commits: one coherent unit of work each, with the reasoning in the message.

## Cadence

Milestones ship in reviewable slices, with a pause after each for the user to
approve or redirect. Do not roll into the next milestone unprompted. State
plainly what was verified versus what was only written.

## Current state

- M1.1 complete and verified end-to-end. CI green (9/9 tests), and the full
  Compose stack was confirmed running on the user's Windows machine: images
  build, Flyway applies both migrations, the API connects as aea_app, and a
  query with no tenant context returns zero rows rather than every row.
- Next: M1.2 auth + RBAC, then M1.3 the PDP, then M1.4 audit + web shell.
- See `docs/architecture/07-milestone-1.md` for exit criteria.

## Environment limits worth knowing

- No Docker daemon in the Claude Code sandbox, so `docker compose up` cannot
  be verified there; CI exercises Postgres via a service container, and the
  user runs Compose locally. Writing the Dockerfiles blind cost three bugs
  (missing Dockerfiles entirely, a Vite proxy pointing at localhost inside its
  own container, and aea_owner lacking database ownership) -- when changing
  anything under docker/, re-read it adversarially and simulate what can be
  simulated against the local PostgreSQL.
- pgvector is not installed in the sandbox; it matters from M3.
- CI must never call a live model: cost per push, and nondeterministic tests.
