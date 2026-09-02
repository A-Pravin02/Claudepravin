# Design canvas

Interface mockups for the chat UI, published as a Claude Design canvas:
https://claude.ai/code/artifact/7eda450a-12e9-4a36-8639-bf8c3b3a7890

## Files

| File | Artboard |
| --- | --- |
| `Main.dc.html` | Full app — sidebar, thread, neuro-symbolic answer with evidence, rules, verification |
| `FastPath.dc.html` | Simple lookup answered on the fast path |
| `RagAnswer.dc.html` | Document-grounded answer with a cited passage |
| `SecurityBlock.dc.html` | Denied query — zero tokens spent |
| `Architecture.dc.html` | Query lifecycle from router to answer |
| `canvas.json` | Layout, titles, annotations |

The seeded `.html` output is generated and gitignored (~2.5 MB, mostly editor
payload). These `.dc.html` sources are the record; re-seed to rebuild.

## Design decisions

Committed direction: a **technical instrument** rather than a conversational
assistant. Dense layout, monospace for anything carrying an identity (rule
keys, route names, token counts, source paths), and a restrained warm-neutral
ground so the route colours are the only strong signal on screen.

Route colour is a categorical scale at fixed lightness and chroma, varying
hue only, so no route reads as more important than another:

| Route | Colour |
| --- | --- |
| FAST | `oklch(0.52 0.13 155)` green |
| RAG / SQL | `oklch(0.52 0.13 245)` blue |
| NEURO_SYMBOLIC | `oklch(0.52 0.13 305)` violet |
| SECURITY_GATE | `oklch(0.52 0.17 28)` red — higher chroma, it is an alarm |

Type: Space Grotesk (display), IBM Plex Sans (body), IBM Plex Mono (identifiers).

Three things the mockups assert deliberately, each traceable to the architecture:

1. **Cost is always visible.** Every answer footer carries tokens, dollars, and
   latency. If adaptive routing is the product thesis, the user should be able
   to see it working.
2. **The complex answer hedges.** R017/R021 establish co-occurrence, not
   causation, so the copy says "coincide with" and names the missing control.
   Verification enforces that rather than letting the model overclaim.
3. **Confidence is labelled `uncalibrated`.** Matches
   `docs/research/open-questions.md` H4 — it is a heuristic aggregate until a
   calibration analysis says otherwise.

Content is fictional (product names, figures, the 11-of-90-days finding). The
route mix on the architecture panel is the hypothesis under test, labelled as
such, not a measured result.
