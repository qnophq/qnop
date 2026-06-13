# ADR-0010: DOCX representation strategy

- **Status:** Proposed (open question)
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

Documents arrive as PDF or DOCX. The browser must render them with line-accurate selection for annotation. There are two defensible approaches, and the design review surfaced a genuine disagreement to resolve in Phase 1.

## Options

- **Option A — PDF as canonical format.** Convert DOCX → PDF on ingest (LibreOffice headless / JODConverter, as a separate process; keep the original DOCX). One rendering + anchoring model (PDF.js text layer + glyph boxes via PDFBox). Simpler frontend; coordinate anchors can break when an edited DOCX is re-converted and the layout shifts.
- **Option B — HTML/DOM rendering + text-range anchoring.** Render DOCX to HTML (docx-preview / mammoth.js) and anchor via text-quote/DOM ranges that survive edits and re-version cleanly; PDF still uses PDF.js. Two rendering paths, but anchoring aligns with the immutable-version model ([ADR-0009](0009-multi-layer-annotation-anchoring.md)).

## Decision

**Deferred.** Phase 0 only defines the `DocumentConverter` and `TextExtractor` ports, keeping both options open. The choice (or a hybrid: PDF snapshot for fidelity + text-quote anchoring for resilience) will be made and recorded as an Accepted update when the ingest pipeline is built in Phase 1.

## Constraints already fixed

- Any DOCX→PDF conversion runs **out-of-process** (LibreOffice is MPL/LGPL — no linking into the AGPL core or commercial add-ons).
- Original uploads are always retained in object storage ([ADR-0005](0005-binary-documents-in-object-storage.md)).
