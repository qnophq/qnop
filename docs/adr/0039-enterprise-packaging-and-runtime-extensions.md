# ADR-0039: Enterprise packaging and runtime UI extension model

- **Status:** Accepted
- **Date:** 2026-07-12
- **Deciders:** qnop core team

## Context

The open-core boundary is decided ([ADR-0002](0002-open-core-via-polyrepo-and-published-spi.md): polyrepo + published SPI; [ADR-0003](0003-agpl-boundary-is-the-spi.md): the SPI is the AGPL line; [ADR-0012](0012-edition-vs-entitlements.md): classpath = edition, entitlements in a signed license token). What was deliberately left open is the *operational* model: how the Enterprise edition is **packaged, distributed and extended** — in particular how enterprise **frontend** components and pages ship, which [ADR-0014](0014-frontend-enterprise-separation.md) only sketched as a direction. This ADR finalizes both.

## Decision

### 1. Distribution: one core, Enterprise as an overlay — never a fork

There are no two product codebases and no edition build flags in the core.

- The **Community distribution** is this repository's `qnop-app` boot artifact / Docker image, unchanged.
- The **Enterprise distribution** is assembled in the private `qnop-enterprise` repository as *the same Community artifact plus enterprise JARs on the classpath* (Spring Boot `loader.path`, or an image layered on the Community image). Enterprise features attach via `@AutoConfiguration` + `@ConditionalOnMissingBean` against the published `qnop-spi`.
- Consequently an on-prem customer upgrades Community → Enterprise by adding licensed JARs; removing them yields Community behavior again. The presence of the JARs *is* the edition ([ADR-0012](0012-edition-vs-entitlements.md)); individual features are gated per customer by the signed license token evaluated inside the enterprise module.

### 2. Feature anatomy: one feature = one JAR, all-inclusive

An enterprise feature ships as a single JAR carrying everything it needs:

- **Backend**: auto-configured beans implementing `qnop-spi` extension points.
- **Schema**: Liquibase changelogs under `db/changelog/enterprise/` picked up by the core's seam (issue #254): the master changelog ends with an `includeAll` of `classpath*:db/changelog/enterprise/` with `errorIfMissingOrEmpty: false` — a no-op without enterprise JARs. Enterprise changesets are namespaced `e####-<feature>-*.yaml` with author `qnop-enterprise`, so ids can never collide with community `####-*` changesets; `includeAll` applies them in filename order after all community migrations.
- **Frontend**: the feature's prebuilt UI bundle served by the JAR as a static resource (see 3).

### 3. Frontend: one Community UI + runtime ESM extensions

There is exactly **one** frontend build — the AGPL `qnop-ui` in this repository. Enterprise UI is loaded at runtime:

- `qnop-ui` defines **slots** (examples: review panel tabs, admin navigation sections, dashboard cards, whole routes with nav entries) and publishes a small versioned npm package **`qnop-ui-spi`** — the frontend analogue of `qnop-spi`: TypeScript types for slots + the extension registry contract, nothing else.
- The host app exposes its singletons (`react`, `react-dom`, `react-router-dom`, MUI core, the `qnop-ui-spi` runtime) through an **import map** in `index.html` — browser-standard ESM, no module-federation tooling.
- An enterprise JAR serves its UI as a prebuilt, hash-versioned **ESM bundle** (built in `qnop-enterprise` against `qnop-ui-spi`, with the shared singletons declared external) under `/enterprise/ui/…`.
- `/config` advertises edition, entitlements ([ADR-0012](0012-edition-vs-entitlements.md)) and the list of extension entry URLs **plus the `qnop-ui-spi` contract version** they were built against. The host dynamically `import()`s compatible entries and mounts what they register into the slots; on a major-version mismatch the extension is skipped and reported instead of breaking the app.
- Entitlement checks in the UI are convenience only — the server remains the authority on every enterprise endpoint.
- The CSP treats `/enterprise/ui/…` as same-origin script content; no third-party origins are involved.

### 4. License line

The Community bundle never contains enterprise JavaScript; enterprise bundles interact with the AGPL host exclusively through the published, versioned `qnop-ui-spi` contract at runtime — mirroring the backend argument of [ADR-0003](0003-agpl-boundary-is-the-spi.md). `qnop-ui-spi` is published and versioned like `qnop-spi` and included in the IP-counsel review noted there.

## Consequences

- Operating story stays trivial: one core release train; an enterprise feature is deployed, updated and revoked as one artifact.
- `qnop-ui` needs no library packaging; the only new published surface is the deliberately tiny `qnop-ui-spi`.
- Version skew between host UI and extensions becomes a managed concern: semver on `qnop-ui-spi`, a compatibility handshake via `/config`, and skip-not-crash behavior.
- Enterprise UI development needs a dev-mode story (loading a local extension bundle against a running Community UI); designed with the first enterprise UI feature.
- The import-map singleton approach is the riskiest element. **Fallback, explicitly kept open:** build-time composition — `qnop-ui` published as a package and a second, commercial frontend build in `qnop-enterprise` (ADR-0014's option). Slots and `qnop-ui-spi` stay identical in that world; only the loading mechanism changes.

## Alternatives considered

- **Two frontend builds (build-time composition)** — safest license separation and full type safety, but requires packaging the whole `qnop-ui` as a library, splits a feature's delivery into JAR + npm package, and doubles the release surface. Kept as the documented fallback.
- **Webpack/Vite Module Federation** — solves the same problem as the import map but adds third-party build magic and couples both sides to bundler internals; plain ESM + import map is the standards-based subset that suffices.
- **iframe micro-frontends per page** — robust isolation but unacceptable UX seams (theming, routing, focus management) for component-level extensions like panel tabs.

## Status notes

Finalizes the directions of [ADR-0012](0012-edition-vs-entitlements.md) and [ADR-0014](0014-frontend-enterprise-separation.md); both remain as recorded context. The Liquibase seam (2) lands with issue #254; slots, `qnop-ui-spi` and the extension loader are built with the first enterprise UI feature (first candidate: e-signature, [ADR-0035](0035-esignature-approval-enterprise-feature.md)).
