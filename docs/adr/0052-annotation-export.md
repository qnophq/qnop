# ADR-0052: Exporting annotations — server-side, in reading order, one model per format

- **Status:** Accepted
- **Date:** 2026-07-28 (extended 2026-07-29)
- **Deciders:** devtank42 (with Claude)

## Context

Annotations only ever existed inside qnop — as a board and a list on the Tasks workspace (issue #547). Both are good for *working* a review and poor for reporting it: handing findings to someone without an account, filtering a few hundred rows, or archiving the outcome of a sign-off. A spreadsheet is the universal format for exactly that.

Three properties of the existing model shape the decision.

1. **Author names are not a property of an author.** Under per-review anonymity (ADR-0038) the same person is a real name to the owner and `Participant 2` to a peer, and `AnnotationService.list` already resolves that per caller while also dropping PRIVATE threads the caller may not see.
2. **Annotations have no position.** They are deliberately position-free (ADR-0009): identity and status are version-independent, and the physical location lives in the placement's opaque `jsonb` anchor. Nothing in qnop sorts by document position, and nothing can — there is no column to `ORDER BY`.
3. **Binary responses are already outside the contract.** `DocumentContentController.downloadOriginal` and `DocumentAttachmentController.serve` are plain controllers by design (ADR-0028); `openapi.yaml` describes JSON exclusively.

## Decision

**Generate the workbook on the server, from the same read path the Tasks page uses, sorted into document reading order, and stream it from a plain controller outside the OpenAPI contract.**

### A format-independent model, and a renderer per format

`AnnotationExportService` performs the authorized read, the ordering and the task-key assignment, and produces an `AnnotationExportModel` — plain data, comment threads already resolved. An `AnnotationExportRenderer` turns that into bytes.

The split is a safety property, not tidiness. A renderer holds no repository and no `EntityManager`, so a new format *cannot* reach past the visibility rules or the identity resolution; the worst a bad renderer can do is lay out what it was given badly. It also moves the layout rules into plain unit tests — no Spring, no database, no Testcontainers — which is where they belong.

The cost is that threads are resolved eagerly when they are requested, so one export holds them all in memory. That is bounded by the same synchronous, single-request design the whole export already has, and it is the price of renderers that are pure functions.

### Server-side, and specifically through `AnnotationService.list`

The alternative — generating the sheet in the browser from the `AnnotationView[]` the Tasks page already holds — was rejected. It would add an `xlsx`/`exceljs` frontend dependency, and it would only ever export the rows currently loaded and filtered. But the deciding reason is privacy: it moves the rendering of identity-bearing data into the client. The frontend only holds already-resolved names, so no leak follows immediately — yet "anonymity that only lives in the frontend is none", and an export is precisely the artifact that outlives the session and gets forwarded.

Building on `list()` rather than on the repository means visibility filtering and identity resolution are inherited **by construction**: there is no code path in the exporter that could forget them.

### Reading order is new behaviour, computed in memory

Rows are sorted by page (`region.surfaceIndex`), then top to bottom (`box.y`), then left to right (`box.x`), then the text offset where a text layer exists, with creation time and id as a stable tail so an unchanged review exports identically twice. Because the anchor is opaque `jsonb`, this is parsed and sorted after loading — `AnnotationPosition` — not expressed in JPQL.

The parser is deliberately forgiving: the anchor format is open by design (ADR-0009 — a region-only image anchor carries no text layer, future formats may add fields), so anything unreadable yields `UNPLACED` and sorts last rather than throwing. One odd anchor must not cost the whole export.

**The version has to be resolved, not defaulted to null.** `list()` only loads placements for a concrete version; without one every anchor comes back empty and the reading order silently collapses into creation order. The exporter therefore resolves the latest version exactly as the Tasks page does.

### Task keys keep creation order

The `#` column carries the `T-1`, `T-2`, … shorthand people use to talk about annotations, and it is assigned in **creation** order — not in the export's reading order — because the key has to agree with the board the user has been looking at. That rule now exists twice, in `tasksModel.taskKeys()` and in the exporter; both are named and tested, and the duplication is the price of putting the key in the sheet at all.

Two columns from the original proposal were dropped rather than duplicated further: a *Board column* (`Open`/`In discussion`/`Resolved`) is a pure derivation of two columns already in the sheet and Excel can filter it itself, and a *Position* ordinal would be identical to the row number while the sheet is in its default order and misleading the moment the user re-sorts.

### Comment threads become a second sheet, off the wire unless asked for

An annotation's thread has no fixed length, so it cannot become columns on the annotation row, and folding a whole conversation into one cell would be neither sortable nor readable. The threads therefore go on their own `Comments` sheet, one row per comment (`#`, `Author`, `Written`, `Comment`), keyed back to the annotation by the same `T-N` shorthand — a relational shape is what a spreadsheet is actually good at.

The rows are read through `AnnotationService.listComments`, for the same reason the annotation rows go through `list`: it applies the PRIVATE-thread visibility check and resolves every author through `ReviewIdentityResolver`, so a pseudonymised reviewer stays pseudonymised in the export by construction. The owner is the one identity that stays real — anonymity never covered them, since it is their review.

That costs one query round per annotation. It is accepted rather than batched: an export is a rare, deliberate action, and re-implementing the visibility rules to avoid the round trips would trade a bounded cost for an unbounded correctness risk. The sheet is opt-in on the wire (`?comments=true`) so an export that does not want it does not pay for it — but the wizard defaults it on, because someone exporting a review usually wants what was said, not just that something was said.

### A plain controller, not the generated contract

`GET /api/v1/documents/{documentId}/annotations/export` mirrors the existing binary downloads: a hand-written `@RestController`, `Content-Disposition: attachment`, deliberately absent from `openapi.yaml` (ADR-0028/0021). Unlike a version download it carries no ETag — annotations change constantly and there is no content hash to hang one on.

The format is a `?format=` query parameter, not a sibling path: one endpoint means one authorization seam rather than one per format, and omitting the parameter keeps every link that shipped before Word working. An unrecognised value falls back to `xlsx` for the same reason unknown field ids are ignored — a client one release ahead of its server should get a file it can open, not a 400.

### Word is a report, not a table in a `.docx`

Rendering the grid into Word would buy nothing over the spreadsheet. The `.docx` is a document meant to be *read*, and — more often than a spreadsheet — edited before it is circulated: a title block naming the review and version, then one section per annotation in the same reading order, the selected facts as a subline, the text as prose, replies indented beneath.

Two deviations from the sheet, both because a page is not a cell: the opening comment is carried in full rather than clipped to a 500-character excerpt, and the thread becomes paragraphs rather than a second sheet. The *content* is identical — same annotations, same order, same task keys, same resolved names — which is what the shared model guarantees.

Headings use direct character formatting plus an OOXML outline level rather than Word's named `Heading 1` style. A blank `XWPFDocument` carries no styles part, and building one programmatically leans on schema types `poi-ooxml-lite` does not guarantee; the outline level is what Word's navigation pane actually reads, so the report stays navigable without that dependency.

## Consequences

**Positive.** One canonical, authorized, privacy-correct artifact, in every format. The export cannot show more than its caller may see, and an anonymous review cannot leak a real name into a file that gets e-mailed onward. Cells are typed (numbers as numbers, timestamps as Excel dates) with a frozen header and auto-filter, so Excel's own sorting works without any post-processing.

**Negative.** The comment thread read is N+1 by construction (see above), now paid by every format that asks for threads. `poi-ooxml` enters the runtime classpath of `qnop-core` — the first Office-format dependency, previously only pinned. The `T-N` rule now lives in two places. Generation is synchronous: a review with very many annotations occupies a request thread for the duration, mitigated by the streaming `SXSSFWorkbook` but not eliminated; an async job would be the answer if that ever becomes real.

**Neutral.** The endpoint is invisible to the generated client, so the frontend calls it through the shared axios instance by hand — a bare `<a href>` would carry no bearer token.

## Related

- **ADR-0038** — per-review privacy; the resolution this export inherits rather than repeats
- **ADR-0009** — the anchor model the reading order is parsed out of
- **ADR-0011** — the workflow whose `AnnotationStatus` the sheet reports
- **ADR-0028** — binary downloads as plain controllers outside the contract

## History

- **2026-07-27** — accepted with the XLSX export (#547).
- **2026-07-29** — extended for Word (#635): the model/renderer split, the `?format=` parameter, and the report layout. The remaining formats (#636–#639) are a renderer and an enum entry.
- **ADR-0021 / ADR-0015** — OpenAPI-first, and why this endpoint is the deliberate exception
- **ADR-0004** — the layering that puts the workbook in the service and leaves the controller streaming
- Issue **#547**
