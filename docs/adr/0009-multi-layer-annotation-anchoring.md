# ADR-0009: Multi-layer annotation anchoring

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

Annotations attach to lines/regions of a document version. A content change produces a **new** version (versions are immutable), and annotations must survive re-versioning rather than orphaning — this is the product's core differentiator and the hardest algorithmic piece. Position-only anchoring breaks as soon as text reflows.

## Decision (direction — to be finalized in Phase 1)

Store multiple independent localization strategies per anchor (as `jsonb`), following the W3C Web Annotation Data Model:

1. **Text-quote anchor** (primary): exact quote + prefix/suffix context — robust to reflow.
2. **Text-position anchor** (secondary): start/end offsets in the extracted text — fast, brittle; a tiebreaker.
3. **Layout anchor**: page + normalized bounding boxes — for visually overlaying the highlight.

Model an annotation's identity as version-independent, with a per-version `AnnotationPlacement(annotationId, documentVersionId, anchorJson, status)`. On a new version, a re-anchoring job re-resolves open annotations (exact → fuzzy); unresolved ones become `ORPHANED` and surface to a human, never silently guessed.

## Consequences

- Discussion/lifecycle persists across versions; physical placement is per version.
- Re-anchoring correctness is the top product risk — start conservative with human-in-the-loop.

## Status note

Recorded now because it shapes the persistence schema. Implementation and the fuzzy-matching details are deferred to Phase 1.
