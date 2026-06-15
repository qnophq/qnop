# ADR-0028: Branding upload pipeline & SVG sanitization

- **Status:** Accepted
- **Date:** 2026-06-15
- **Deciders:** qnop core team (with Claude)

## Context

Issue #15 introduced the `application_asset` storage (one row per `BrandingSlot`, bytes in Postgres `bytea`, ADR-0024). Issue #23 adds the upload pipeline and the public read path. Two things make this security-sensitive: operator-uploaded **SVG is XML that browsers execute** (XSS/XXE), and the read path is served on **authentication-free** surfaces (the login page, OG metadata).

## Decision

- **Validation pipeline (`BrandingService`).** On upload: sniff the real content type from the **bytes** (PNG/WebP magic, `<svg` for SVG) — the client-declared `Content-Type` is never trusted; enforce the byte-size cap (512 KiB, `BrandingLimits`); **sanitize SVG**; bound the pixel dimensions (`ImageDimensions`: ImageIO for raster, `viewBox` for SVG); SHA-256; then **delete-then-insert** so a slot holds exactly one current asset.
- **SVG sanitizer (`SvgSanitizer`).** An **allowlist** sanitizer over an **XXE-hardened** parser: DOCTYPEs are rejected outright (`disallow-doctype-decl`) and all external DTD/entity/stylesheet access is disabled, so entity expansion and file/SSRF disclosure are impossible. Only presentational elements survive; `<script>`, `<foreignObject>`, `<style>`, `<image>`, `<a>` and anything else are dropped, along with `on*` handlers, inline `style`, and any `href`/`xlink:href` that is not a local `#fragment`.
- **Endpoints are hand-written controllers, not generated from the OpenAPI JSON contract.** `POST`/`DELETE /api/v1/admin/branding/{slot}` (superadmin) carry multipart uploads; `GET /api/v1/branding/{slot}` returns binary with an **ETag (the SHA-256) + 304** and `Cache-Control: no-cache` (revalidate-always). Multipart and binary-with-caching sit outside the published JSON surface (ADR-0021), so they are plain `@RestController`s — still web→service only (ArchUnit-clean). The read path is `permitAll`; the admin namespace is covered by the existing `/api/v1/admin/**` → `SUPERADMIN` rule.

## Consequences

- An uploaded SVG cannot carry script, event handlers, external entities, or external references; combined with the strict CSP (ADR-0022) the login page is safe to render branding inline.
- Re-uploading a slot replaces it; the new SHA-256 changes the ETag, busting caches.
- WebP dimensions are best-effort (stock ImageIO has no WebP reader) — the size cap still bounds the payload; only the pixel-dimension check is skipped when dimensions are unknown.
- These two controllers are the first that do not implement a generated interface; the deviation is scoped to binary/multipart I/O and documented here.

## Alternatives considered

- **A third-party SVG/HTML sanitizer.** Rejected: no well-maintained permissive-licensed pure-Java SVG sanitizer fits, and an allowlist over the JAXP parser is small, auditable, and dependency-free (keeps the AGPL/commercial boundary clean, ADR-0007).
- **Object storage for branding bytes.** Already rejected in ADR-0024 (branding is config-like; keep it in Postgres).
- **Generating the endpoints from OpenAPI.** Rejected for these two: multipart upload and binary ETag/304 responses are awkward to express and add no value to the JSON contract consumers.
