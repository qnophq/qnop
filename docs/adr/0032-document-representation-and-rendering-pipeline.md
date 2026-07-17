# ADR-0032: Document representation & rendering pipeline

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

The review surface must show a document **faithfully** (legal/compliance documents demand visual fidelity to the original) *and* let annotations attach to a stable substrate that survives re-versioning ([ADR-0009](0009-multi-layer-annotation-anchoring.md)). The initial scope is textual documents — PDF first, then DOCX and Markdown — but the product intends to also review non-textual formats later (images, and possibly more), which have **no text layer at all**. We need one representation model that:

- preserves the original's look,
- gives annotations both a text anchor (when text exists) and a geometric anchor (always),
- is the same shape regardless of source format, so a new format is an added implementation, not a core rewrite,
- keeps the frontend "dumb" — it renders pixels and boxes, it is not the authority on where text is.

## Decision

**Hybrid rendering with a server-authoritative, format-agnostic canonical representation.**

1. **Hybrid rendering.** The client renders the *original* for fidelity (PDF.js for PDF; `<img>` for images; converted PDF for DOCX, see [ADR-0010](0010-docx-representation-strategy.md)). It does **not** derive anchoring data from that rendering.

2. **`RenderedDocument` is the canonical model**, produced server-side at ingest and stored as part of the immutable `DocumentVersion`:

   ```
   RenderedDocument
    └── Surface[0..N]              a PDF page / an image plane / a converted DOCX page
         ├── width, height         intrinsic size; anchors are normalized to 0..1 of it
         ├── visual                how the pixel image is produced (pdf.js / img / converted)
         └── textLayer?            OPTIONAL: ordered spans { text, charOffsetRange, box(0..1) }
   ```

   - **Coordinates are normalized to 0..1** of each surface, so a highlight box sits correctly independent of the client's zoom/DPI.
   - **The text layer is optional.** An image is simply "a document with one surface and no text layer"; region anchoring still works (ADR-0009).

3. **Server-authoritative extraction at ingest.** On upload, the server extracts per surface the text spans (text + offsets) **and** their normalized boxes, using **Apache PDFBox** (Apache-2.0, AGPL-compatible) for PDF. This makes anchoring and re-anchoring deterministic and server-side, and keeps every format funneling into the same `RenderedDocument`. Extraction runs as a durable async job ([ADR-0033](0033-durable-async-job-execution-on-postgres.md)).

4. **A per-format `DocumentExtractor` SPI** ([ADR-0003](0003-agpl-boundary-is-the-spi.md)) turns a raw upload into a `RenderedDocument`. Community ships PDF, image, and Markdown extractors (and DOCX via out-of-process conversion); exotic formats are an enterprise implementation behind the same seam.

5. **Server-mediated upload and serving.** Uploads go client → server → object store: the server validates (MIME/size), computes the content hash, creates the `DocumentVersion`, and enqueues extraction. The original binary and any derived render are served **through the server with per-request authorization**, never via presigned URLs — a review document must only be reachable by its participants, and a shareable presigned URL would bypass that authz. (This builds on the object-store decision in [ADR-0005](0005-binary-documents-in-object-storage.md), which already defines the `StorageProvider` SPI.)

## Consequences

- The frontend is format-agnostic: it renders the original pixels and overlays highlights from stored normalized boxes — the same code for PDF, image, or converted DOCX.
- Re-anchoring (ADR-0009) and inter-version diff ([ADR-0034](0034-inter-version-diff.md)) both run server-side against the stored text layer — they get it for free.
- Ingest is heavier (server-side extraction per upload) and must be async + retryable; the PDFBox box coordinates must agree pixel-wise with the PDF.js rendering — guarded by the 0..1 normalization and tests.
- Adding a format = implementing one `DocumentExtractor`; the viewer, anchoring, diff, and workflow are untouched.
- Serving through the server keeps the server in the bandwidth path; presigned GET with short TTL remains a deliberate **later** optimization (Phase 2) if load demands it, not a default.

## Alternatives considered

- **A — Render the original in the client and derive anchoring there (PDF.js text layer live).** Rejected as the authority: format-specific (images/DOCX each need a bespoke client path), and re-anchoring would still need server-side text. We keep client rendering for *pixels only*.
- **B — A canonical server-rendered representation the client displays (DOCX/PDF → unified HTML).** Rejected for the core: loses the original's exact layout, which is non-negotiable for the documents qnop reviews.
- **Storing only text-quote anchors, no boxes.** Rejected: images have no quotes, and visually locating a highlight needs geometry; region/box anchoring must be a first-class, always-present layer.

## Amendment (2026-07-16, shipped vs. planned extractors)

Decision point 4 lists the intended Community extractor set; as of this date only the **PDF extractor** (`PdfBoxDocumentExtractor`) is shipped. The Markdown and DOCX extractors remain planned Community scope per [ADR-0010](0010-docx-representation-strategy.md). The **image** extractor's placement (Community vs. Enterprise) was re-opened by the PDF-first roadmap decision and is no longer settled by this ADR.
