# ADR-0010: DOCX representation strategy

- **Status:** Accepted
- **Date:** 2026-06-13 (resolved 2026-06-28)
- **Deciders:** qnop core team; resolved by bigpuritz, devtank42 (with Claude)

## Context

Documents arrive as PDF, DOCX, or Markdown. The browser must render them faithfully with region-accurate selection for annotation. Phase 0 left a genuine disagreement open: PDF-as-canonical (convert DOCX→PDF) vs. HTML/DOM rendering (docx-preview/mammoth) with DOM-range anchoring. The Phase-0 record explicitly named a **hybrid** — "PDF snapshot for fidelity + text-quote anchoring for resilience" — as the likely resolution.

## Decision

**Resolve to the hybrid, via the canonical pipeline of [ADR-0032](0032-document-representation-and-rendering-pipeline.md).**

- **DOCX is converted to PDF on ingest**, out-of-process (LibreOffice headless / JODConverter as a separate process — never linked, see constraint below). The original DOCX is always retained in object storage ([ADR-0005](0005-binary-documents-in-object-storage.md)).
- The converted PDF then flows through the **same `RenderedDocument` pipeline as native PDF**: PDFBox extracts the per-surface text spans + normalized boxes; the client renders the converted PDF with PDF.js. DOCX is therefore *not* a second rendering/anchoring path — it funnels into the one canonical model.
- **Anchoring resilience comes from the text-quote layer** ([ADR-0009](0009-multi-layer-annotation-anchoring.md)), not from layout coordinates: when an edited DOCX is re-converted and the layout shifts, annotations re-anchor on quote + context, and the per-version `AnnotationPlacement` carries the new boxes. This neutralizes the main weakness of "PDF as canonical" (coordinate drift on re-conversion).
- Conversion runs as a durable async job ([ADR-0033](0033-durable-async-job-execution-on-postgres.md)), like every other extraction.

Concretely: the `DocumentExtractor` SPI has a DOCX implementation = "convert to PDF out-of-process, then delegate to the PDF extractor". Markdown takes an HTML path; PDF and images are native.

## Consequences

- One rendering model and one anchoring model for PDF and DOCX — simpler frontend, shared diff and re-anchoring.
- DOCX fidelity depends on LibreOffice's conversion quality; acceptable, and the original is always downloadable.
- Adds an out-of-process LibreOffice dependency to the ingest environment (containerized); only invoked for DOCX, async.
- DOCX is **not** in the first vertical slice (PDF-first); this ADR fixes the *direction* so the seam (`DocumentExtractor`, convert-then-extract) is built right from the start.

## Constraints already fixed

- Any DOCX→PDF conversion runs **out-of-process** (LibreOffice is MPL/LGPL — no linking into the AGPL core or commercial add-ons).
- Original uploads are always retained in object storage ([ADR-0005](0005-binary-documents-in-object-storage.md)).

## Alternatives considered

- **Option B — HTML/DOM rendering + DOM-range anchoring (docx-preview/mammoth).** Rejected for the core: a second rendering and anchoring path, and it forfeits exact original layout. Re-anchoring resilience is already solved by the text-quote layer without it.
- **Render DOCX in the client directly.** Rejected: format-specific client path, against the format-agnostic, server-authoritative representation of ADR-0032.
