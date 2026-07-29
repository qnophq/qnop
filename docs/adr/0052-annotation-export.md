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

Cells carry the full text. An earlier cap clipped the summary column to 500 characters on the theory that a spreadsheet wants short cells; that was the wrong call, because an export which quietly drops the second half of a finding is worse than a tall row. Paragraph breaks survive, the long-text columns wrap and align to the top, and nothing is cut until Excel's own 32 767-character ceiling — which POI enforces by throwing, so the cut is unavoidable and is marked with an ellipsis rather than made silently.

Two columns from the original proposal were dropped rather than duplicated further: a *Board column* (`Open`/`In discussion`/`Resolved`) is a pure derivation of two columns already in the sheet and Excel can filter it itself, and a *Position* ordinal would be identical to the row number while the sheet is in its default order and misleading the moment the user re-sorts.

### Comment threads become a second sheet, off the wire unless asked for

An annotation's thread has no fixed length, so it cannot become columns on the annotation row, and folding a whole conversation into one cell would be neither sortable nor readable. The threads therefore go on their own `Comments` sheet, one row per comment (`#`, `Author`, `Written`, `Comment`), keyed back to the annotation by the same `T-N` shorthand — a relational shape is what a spreadsheet is actually good at.

The rows are read through `AnnotationService.listComments`, for the same reason the annotation rows go through `list`: it applies the PRIVATE-thread visibility check and resolves every author through `ReviewIdentityResolver`, so a pseudonymised reviewer stays pseudonymised in the export by construction. The owner is the one identity that stays real — anonymity never covered them, since it is their review.

That costs one query round per annotation. It is accepted rather than batched: an export is a rare, deliberate action, and re-implementing the visibility rules to avoid the round trips would trade a bounded cost for an unbounded correctness risk. The sheet is opt-in on the wire (`?comments=true`) so an export that does not want it does not pay for it — but the wizard defaults it on, because someone exporting a review usually wants what was said, not just that something was said.

### Presentation is configured, not assumed

Two things an export cannot get right by guessing, so the wizard asks:

**The date convention, and the zone it is expressed in.** `03/04/2026` is two different days depending on who reads it, and a report going into a German sign-off wants dots. `ExportDateFormat` carries two patterns per entry, because the formats write dates in genuinely different ways: a document renders a string, while a spreadsheet writes a *typed* date and the choice only changes its display — which is what keeps Excel's sorting and date filters working.

The zone travels with it, because a convention without one is half an answer: a comment written at 23:40 UTC happened on a different day for the reader in Berlin. The wizard preselects the reader's own zone by reusing the resolution that already exists for the whole app — profile preference → operator default → UTC (ADR-0041) — rather than resolving it a second way, and offers every zone the runtime knows for the case where the report is for someone else. An explicitly chosen zone persists and outranks the account's; a stored zone the runtime no longer recognises yields back to it.

On the wire the zone is a parameter and its absence means UTC. The server does *not* resolve the caller's preference behind their back: a predictable default is worth more on an endpoint whose whole contract is "what you asked for is what you get", and the UI always sends what it showed.

**The logo.** Not every export is a document that should carry branding — an extract for a pivot table is not — so it is a switch. Whether the switch is even offered is a property of the format, not a preference: `AnnotationExportFormat.supportsLogo()` is false for Markdown and CSV, which are text and have nowhere to put an image. A control that silently does nothing is worse than an absent one, and the service checks the same flag, so the rendition is not even fetched for a format that could not use it.

In the spreadsheet the logo is a floating layer in the top-right corner of *every* sheet, at the image's own pixel size — the anchor is computed from the picture, never the picture squeezed into the anchor. It costs the grid no rows, so a sheet has the same shape whether or not it is branded.

Pinning it against cell changes needed a detour. `ClientAnchor.setAnchorType` is the obvious way to say "do not move or resize", and the streaming workbook silently drops it — measured across all four values, before and after `createPicture`. The attribute it stands for, `editAs` on the anchor element, is therefore written directly through POI's object model. POI's own reader does not report it back either, so the test asserts against the drawing XML in the produced file, which is what Excel actually reads.

### Four formats, not six

CSV and Markdown were dropped (#636/#638) after Word shipped. CSV is a strictly worse Excel for this data — no typed dates, and nowhere to put the comment threads that need a second sheet — and Markdown duplicates what the review UI already renders. Excel and Word cover the two things an export is actually for: a grid to filter and a document to read; HTML (#637) and PDF (#639) remain because they answer "opens anywhere" and "archive this", which neither shipped format does.

### Inline images travel with the text

Comment bodies are Markdown, and a screenshot pasted into a review is frequently the substance of the comment. The original flattening stripped image references along with the rest of the markup, so those comments exported as a sentence pointing at nothing.

Bodies are now *split* rather than stripped (`ExportSegment`), and each format decides: Word embeds the picture where the author put it, the spreadsheet writes `[screenshot.png]` in the cell. A floating picture in a filterable grid would be worse than none — the first sort detaches it from its row — but silence was the bug.

The bytes arrive through the model, resolved by `ExportImageResolver`, for the same reason the logo does: a renderer that could read an attachment could read one its caller may not see. Two rules bound that resolver, and both are tested. Only this app's own attachment URLs are followed — otherwise an export becomes a fetcher for whatever URL someone typed into a comment, which is server-side request forgery with a review as the delivery vehicle. And every lookup is scoped to the document being exported, so a comment naming another review's attachment resolves to nothing instead of to a file the reader cannot otherwise open.

WEBP is converted to PNG on the way in, using the ImageIO reader ADR-0053 already put on the classpath; no Office format embeds WEBP.

**Other attachments are linked, not embedded.** A PDF or a spreadsheet attached to a comment becomes a marked row — paperclip, filename, type and size — whose name is a hyperlink. The link points at a **page in the app** (`/attachments/{documentId}/{attachmentId}`), never at the attachment API behind it: that endpoint is bearer-authenticated, and a browser following a link out of a Word file sends no token, so a direct link would land the reader on a 401 instead of a file. The page lives inside the protected routes, so an unauthenticated visitor is sent to the login form and returned afterwards by the redirect every deep link already uses, and only then is the file fetched with the token attached.

The link must also be **absolute**, and that is a correctness requirement rather than a nicety: Word resolves a relative target against the document's own location, so `/api/v1/…` in a downloaded report becomes `file:///api/v1/…` and points at the reader's disk. The origin comes from `general.base_url`; where an operator has not set it, the origin the export was just downloaded from is used instead — unlike a notification mail, whose link is followed by someone who did not make the request, this link is read by the person who asked for the file. With neither available the file is named but not linked. OOXML *can* carry an OLE object, but POI exposes only `getAllEmbeddedParts()` for reading; writing one means hand-authored `w:object` XML, a package part, a relationship and an icon image, on schema types `poi-ooxml-lite` does not guarantee. Weighed against a report that would then mail binaries around, the link is the better answer: a reader with access is one click away, and a reader without at least knows the file exists — which the bare filename this replaces did not convey.

### The filename is the user's, within limits

The default is `<slug>-annotations.<ext>`, derived from the review's title, because a folder full of exports has to stay legible. It is only a default: a report going to a customer is rarely best named after an internal document title, so the wizard shows the name and lets it be replaced.

The extension is not part of that choice. It follows the format, because a file named `.pdf` that contains a workbook lies about its own bytes.

Sanitizing is not cosmetic here — the name lands in a `Content-Disposition` header, where a newline is header injection and a separator aims the download outside the downloads folder. `ExportFilename` folds everything outside a conservative set away rather than escaping it, and the header is UTF-8 encoded so non-Latin names survive. Words that merely *look* alarming are left alone: `..` sitting inside a filename with no separator around it is text, and stripping it would be theatre.

The filename is deliberately **not** persisted with the other wizard settings. Those are preferences; a filename belongs to one review, and carrying last week's name onto a different document would be a trap.

`ExportFilename` does not reuse `UserSlugs`. That derivation carries rules that exist for URL routing — a `-user` suffix for short names, a guard against UUID-shaped results — and a filename has no business inheriting them.

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
- **ADR-0041** — the display-timezone resolution the wizard's preselection reuses
- **ADR-0053** — the branding logo's raster rendition, which the Word report places in its header

## History

- **2026-07-27** — accepted with the XLSX export (#547).
- **2026-07-29** — extended for Word (#635): the model/renderer split, the `?format=` parameter, and the report layout. The remaining formats (#637, #639) are a renderer and an enum entry.
- **2026-07-29** — the date convention, its timezone, the branding logo and the filename became per-export choices, all format-independent.
- **2026-07-29** — inline images in annotations and comments are exported rather than stripped; other attachments are linked.
- **ADR-0021 / ADR-0015** — OpenAPI-first, and why this endpoint is the deliberate exception
- **ADR-0004** — the layering that puts the workbook in the service and leaves the controller streaming
- Issue **#547**
