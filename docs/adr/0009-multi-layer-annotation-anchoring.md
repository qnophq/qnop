# ADR-0009: Multi-layer annotation anchoring

- **Status:** Accepted
- **Date:** 2026-06-13 (finalized 2026-06-28)
- **Deciders:** qnop core team; finalized by bigpuritz, devtank42 (with Claude)

## Context

Annotations attach to lines/regions of a document version. A content change produces a **new** version (versions are immutable, [ADR-0011](0011-review-workflow-state-model.md)), and annotations must survive re-versioning rather than orphaning — this is the product's core differentiator and the hardest algorithmic piece. Position-only anchoring breaks the moment text reflows. The model must also cover **non-textual documents** (images), which have no text to quote at all.

## Decision

Store **multiple independent localization strategies per anchor** (as `jsonb`), following the W3C Web Annotation Data Model. The anchor is bound to *content + context*, not to a coordinate:

1. **Region anchor (universal, always present):** `{ surfaceIndex, box }` in normalized 0..1 coordinates of the surface ([ADR-0032](0032-document-representation-and-rendering-pipeline.md)). This is the one layer that *always* exists — for an image it is the only anchor.
2. **Text-quote anchor (primary when a text layer exists):** exact quote + prefix/suffix context — robust to reflow.
3. **Text-position anchor (secondary):** start/end offsets in the extracted text — fast, brittle; a tiebreaker.

So region anchoring is the universal base layer and text-quote is an *additional* strategy available only when the surface has a text layer — not the other way round.

**Identity vs. placement.** An annotation's identity (and its comment thread, status) is **version-independent**: `Annotation`. Physical location is **per version**: `AnnotationPlacement(annotationId, documentVersionId, anchorJson, status)`.

**Re-anchoring** runs as a **durable async job** ([ADR-0033](0033-durable-async-job-execution-on-postgres.md)) on every new version, resolving each open annotation through a cascade:

- **Exact** — quote + context occur unchanged exactly once → unique re-placement.
- **Fuzzy** — slightly changed/duplicated text; the similarity-best match weighted by context wins if above a threshold and unambiguous → re-placed and flagged **MOVED** (changed) for the reviewer.
- **Orphaned** — no match above threshold → status `ORPHANED`; **never silently guessed or mis-placed**, surfaced to the owner for manual handling.

The placement lifecycle is therefore:

```
PENDING → PLACED | MOVED | ORPHANED | FAILED
```

`PENDING` exists because the version is visible immediately while the job runs (ADR-0033). For **images**, there is no content-based re-anchoring in Community: the region keeps its 0..1 coordinates and is flagged "to review" on a new version (visual region matching is enterprise/AI behind the SPI).

## Consequences

- Discussion/lifecycle persists across versions; physical placement is per version and recomputed deterministically.
- Re-anchoring correctness is the top product risk — the cascade is conservative and **human-in-the-loop** (orphans are honest, never wrong).
- The same anchor model serves text and non-text documents; adding image review needs no anchoring change.
- A version with `PENDING`/un-decided placements is "not yet finalizable" (ADR-0011), a clean precondition rather than a special case.

## Alternatives considered

- **Position-only anchoring.** Rejected: breaks on any reflow.
- **Text-quote as the sole/primary strategy with layout as a mere fallback.** Rejected: images have no quote; geometry must be a first-class, always-present layer.
- **Synchronous re-anchoring at upload.** Rejected: blocks the upload for large documents/many annotations; async with a `PENDING` lifecycle is the chosen shape ([ADR-0033](0033-durable-async-job-execution-on-postgres.md)).
- **Auto-placing low-confidence matches.** Rejected: a mis-attached objection on a contract is worse than a visible orphan.
