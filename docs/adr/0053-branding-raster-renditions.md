# ADR-0053: Raster renditions of branding assets, produced on upload

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** devtank42 (with Claude)

## Context

The Word export (#635) should carry the operator's logo. It cannot.

Word embeds PNG, JPEG, GIF, BMP, EMF and a few more — **not SVG**, and not WEBP. qnop's branding accepts exactly the three formats Word is worst at: PNG, WEBP and SVG (`BrandingLimits`), and the *bundled factory logos are SVG*. So out of the box, and for most operators — SVG is what a logo is normally delivered as — there is nothing embeddable to place.

That leaves three ways out, and the choice is not really about Word:

1. **Accept only raster branding for documents.** No new dependency. An operator who uploaded an SVG sees their logo everywhere in qnop except in the files they send to customers — a silent, surprising hole.
2. **A separate "document logo" branding slot** restricted to raster. Also dependency-free, and the operator controls the print appearance. But it is one more thing to configure, and it is configuration that exists only to work around a file-format limitation.
3. **Convert server-side.** Everything works, out of the box, with nothing to configure — at the cost of an SVG rasterizer in the runtime.

## Decision

**Convert server-side, at upload time, and store the rendition.**

### Apache Batik for SVG, TwelveMonkeys for WEBP

`batik-transcoder` + `batik-codec` (Apache-2.0) rasterize SVG. The JDK additionally has **no WEBP reader at all**, so `com.twelvemonkeys.imageio:imageio-webp` (BSD-3) is on the runtime classpath — without it, an accepted upload format would vanish from every export, which is precisely the silent hole option 1 was rejected for. Both licenses are permissive, so neither touches the commercial add-on path (ADR-0007).

Batik is a large dependency with a history of CVEs, and its input here is operator-supplied. Two things bound that. SVGs are already sanitized on the way in (`SvgSanitizer`, ADR-0028), so the parser never sees scripts or external references. And the transcoder is explicitly pinned shut — external resources refused, scripts disallowed, `onload` not executed — stated in code rather than inherited, so a Renovate bump that changes a Batik default cannot quietly change qnop's posture.

### Converted once, on upload, into a nullable column

`application_asset.raster_content` holds the PNG. It is derived data: nullable, safe to drop, recomputed on the next read.

The alternative — convert per export — would spend real CPU on every download for a result that cannot change. An in-memory cache would avoid the column but pay that cost once *per instance*, which is the wrong shape for a deployment that scales out.

There is no backfill. Assets stored before the column existed get their rendition the first time one is asked for, written back in **its own transaction** (`REQUIRES_NEW`): callers are read-only — an export is — and the write-back has no business being rolled back with whatever the caller was doing. A migration that rasterized every stored asset would have to run application code inside Liquibase, which is exactly the coupling changesets exist to avoid.

The bundled defaults are classpath resources with no row to write back to and no way to change without a redeploy, so their renditions live in an in-memory map.

### The logo reaches the renderer through the model

`AnnotationExportModel` carries `logoPng`. A renderer does not call the branding service, for the same reason it does not call a repository (ADR-0052): a renderer that could reach one service could reach any of them, and the guarantee that a new format cannot bypass the visibility rules rests on it having nothing to reach.

### Failure is always silent and always local

Rasterizing returns `Optional.empty()` rather than throwing, and the header is skipped when there is no logo. A logo is decoration; a download that fails because a branding asset would not convert is a worse outcome than a document without a logo.

## Consequences

**Positive.** Every branding format works in every export, with nothing to configure and no second slot to maintain. Conversion is paid once per asset, ever, and shared across instances. The Word report carries the logo in the page header, so a page that has left the document still says where it came from.

**Negative.** Batik is a heavy runtime dependency (~8 MB, a dozen modules) with a CVE history, added for what is visually a decoration; it needs watching in Renovate and the security scan. Two new dependencies rather than one, because WEBP support in the JDK does not exist. A schema column that is a cache.

**Neutral.** Renditions are PNG at a fixed 600px width — generous for print, negligible next to the 512 KB upload limit.

## Related

- **ADR-0028** — branding uploads, SVG sanitization, assets as `bytea`
- **ADR-0052** — the export's model/renderer split, which decides how the logo travels
- **ADR-0007** — permissive dependencies only; both additions comply
