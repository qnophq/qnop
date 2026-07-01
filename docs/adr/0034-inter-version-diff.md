# ADR-0034: Inter-version diff

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

A reviewer's most common question on a new version is "what changed since the last one?". The design prototype has a dedicated diff surface. This is a **Community-core** feature, not enterprise. We must decide what is diffed, how it is shown, and when it is computed — and avoid confusing it with re-anchoring.

The architecture already gives us a strong starting point: every `DocumentVersion` carries a server-extracted text layer ([ADR-0032](0032-document-representation-and-rendering-pipeline.md)), so both sides of a comparison are already on hand.

## Decision

**A text diff over the extracted text layer, visually located on the original via the stored boxes.**

- **Substrate: the extracted text layer.** A Myers/LCS diff (library: `diff-match-patch` or `java-diff-utils`, both Apache-2.0) over the two versions' text, at word/sentence granularity. Format-agnostic — PDF, DOCX, and Markdown all expose a text layer, so one diff path serves all text formats.
- **Visually located, not a bare two-column view.** The diff yields changed spans; we map each changed span back to its normalized box and highlight the change (inserted / deleted / modified) **on the rendered original**. The boxes from ADR-0032 make this almost free; a plain side-by-side text view is the fallback, not the primary presentation.
- **Images: no semantic diff.** With no text layer, the Community presentation is **side-by-side** (a simple pixel overlay may come later). Content-based visual region diff is AI/enterprise territory behind the SPI.
- **Computed on-demand and cached.** Because versions are immutable, a diff between any two versions is **stable forever** → compute lazily, cache indefinitely (never invalidated). Arbitrary pairs are diffable (v1↔v3), not only adjacent ones. Large diffs may be offloaded to the durable job queue ([ADR-0033](0033-durable-async-job-execution-on-postgres.md)) — no new infrastructure.

**Diff is not re-anchoring.** Both read the two text layers, but answer different questions: re-anchoring is *"where is this one annotated spot now?"* (per-anchor), diff is *"what changed overall?"* (global). They stay separate operations; a known diff may later optimize re-anchoring (which regions are stable/moved/deleted), but that coupling is deferred.

## Consequences

- The diff reuses data we already store (text layer + boxes) — little new machinery, and it lands visually on the real document instead of as raw text columns.
- Works uniformly across PDF/DOCX/MD; images degrade honestly to side-by-side.
- Caching is trivially correct (immutable versions); cache entries are keyed by the (from-version, to-version) pair.
- A semantic/structural diff (clause-aware) and image region diff are explicitly **deferred** (enterprise / later).

## Alternatives considered

- **Diffing the rendered pixels.** Rejected as the core: not semantic, noisy on re-flow, and unavailable as text for downstream use. Kept only as the image fallback.
- **Diffing the source bytes (PDF/DOCX internals).** Rejected: format-specific and meaningless to a reviewer; the extracted text is the human-relevant substrate.
- **Precompute every adjacent diff at ingest.** Rejected as the default: wastes work on versions never compared; on-demand + permanent cache covers it, with the option to offload heavy cases to the job queue.
