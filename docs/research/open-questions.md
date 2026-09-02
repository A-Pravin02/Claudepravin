# Open Questions and Unproven Claims

Discipline: nothing in this file may be stated as fact in marketing, a paper,
or a customer conversation until the corresponding experiment has run.

## H1 — Adaptive routing reduces cost without reducing reliability

**Claim:** System D (adaptive) achieves accuracy ≥ System C (always-on
neuro-symbolic) at materially lower cost per correctly-answered authorized
query.

**Test:** benchmark corpus, 4 systems, paired comparison, bootstrap CIs.
**Falsifiable by:** D's accuracy being significantly below C's, or the cost gap
being smaller than the engineering cost of the routing layer.
**Status:** UNTESTED. This is the thesis. Design the experiment before the
implementation, so the result cannot be rationalised afterwards.

## H2 — Complexity is predictable pre-execution

**Claim:** A feature-based score computed without retrieval predicts the
required reasoning depth well enough to route.
**Risk:** it may be substantially unpredictable — depth often depends on what
the data turns out to look like.
**Mitigation already in the design:** budgets + escalation, so a wrong prior
costs money rather than correctness.
**Measure:** correlation between predicted complexity and actual steps
consumed; escalation rate by predicted bucket.

## H3 — Threshold values

0.30 / 0.65 are placeholders with no empirical basis. Fit them on the benchmark
with an explicit asymmetric loss (missed escalation weighted ~5–10× false
escalation) and report the chosen weighting.

## H4 — `system_confidence` is meaningful

Currently a heuristic aggregate. Before it is shown to users as a percentage,
run a calibration analysis (reliability diagram, ECE). Until then it is
labelled `calibrated: false` in the API and displayed as a qualitative band in
the UI, not a number.

## H5 — Neural/symbolic conflicts are frequent enough to matter

If conflicts occur in <1% of queries, the fusion layer is research furniture
rather than a product feature. Instrument the rate before investing further.

## H6 — Cost is a durable differentiator

Inference prices fall roughly an order of magnitude per year. Model the thesis
under a 10× price drop: does adaptive routing still pay for itself? If the
answer is no, the pitch must lead with governance and verification, and cost
becomes the entry argument rather than the moat. **Do this analysis early — it
is a strategy question, not an engineering one.**

## H7 — Semantic caching may dominate routing savings

A cache on repeated queries could deliver larger savings than routing, more
cheaply. Deliberately excluded from the MVP so the benchmark isolates routing.
Measure both independently in V2; be honest about which effect is larger.
