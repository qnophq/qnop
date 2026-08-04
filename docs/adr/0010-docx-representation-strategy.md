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

### Amendment (2026-08-05, qnop-ee#20): Markdown is Enterprise scope

The decision above lists Markdown among the formats Community ingests ("Markdown takes an HTML path"), and issue #344 tracked building that extractor. Both are now wrong about *where*: Markdown moved to the private enterprise repository, so Community reviews PDF and DOCX and nothing else.

What does **not** change is the reasoning. Markdown was always going to be the format that proved the seam, because it is the one that cannot funnel through a PDF conversion the way DOCX does — it has no layout to preserve, so it takes the HTML path this ADR named. That the seam has to carry a format the canonical pipeline does not natively fit is exactly why `DocumentExtractor` is a published SPI (ADR-0003/0046) rather than an internal interface. An enterprise module implementing it is the case this architecture was drawn for, not a departure from it.

The practical consequence for this repository: `GET /api/v1/config` keeps reporting `supportedFormats` from the extractors actually registered, so a deployment with the enterprise module simply advertises one more. Nothing here needs a flag, a branch, or a stub.

### Amendment (2026-07-30, issue #343): the conversion sits one step before the SPI

Implementing this showed the last sentence to be wrong about *where* the seam goes, and right about everything else.

`DocumentExtractor.extract` returns a `RenderedDocument` — geometry and text spans, never pixels. A DOCX extractor would therefore convert, hand over the spans, and throw the converted PDF away, leaving the viewer with nothing to render: this ADR's own decision is that the client renders the *converted PDF* with PDF.js, so that PDF is an artifact the pipeline has to keep.

So the conversion runs in the pipeline, immediately before extraction (`DocumentRenditionService`), and the published SPI (ADR-0003/0046) is untouched. A version now points at two objects: `storage_key`, the upload, which stays downloadable as this ADR requires, and `rendition_storage_key`, the PDF it is viewed and extracted through. Everything downstream — anchoring, diff, workflow, the viewer — still sees one model and one format.

Three consequences worth recording:

- **The conversion is stored, not recomputed.** It is not byte-deterministic (a PDF carries a creation date), and keys are content-addressed, so re-converting would mint a second key, orphan the first, and shift the pages under annotations already placed on them. The key is written once and reused on replay, which is what keeps the extraction job idempotent under ADR-0033.
- **Whether a deployment accepts DOCX is a server property**, not a release constant — the same shape ADR-0052 arrived at for PDF export. `GET /api/v1/config` reports it in `supportedFormats`, the upload UI offers only those, and a server with no converter refuses a Word upload with **415** at upload time rather than accepting a document it could never render and failing a job the user waited for.
- **A conversion failure is retryable, with one exception.** No converter, or a run that timed out, is the environment and heals on its own; a converter that ran to completion and produced no PDF is how LibreOffice says it could not read the file, and that is failed permanently — otherwise the version would sit in PENDING forever.

Uploads are still decided by their bytes, never by the declared type. DOCX is a ZIP, so the check is for the `word/document.xml` part rather than for the ZIP magic, which would also accept spreadsheets and arbitrary archives.

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
