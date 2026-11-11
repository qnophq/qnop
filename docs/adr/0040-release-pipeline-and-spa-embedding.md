# ADR-0040: Release pipeline & SPA embedding

- **Status:** Accepted
- **Date:** 2026-07-16
- **Issue:** [#495](https://github.com/qnophq/qnop/issues/495)

## Context

qnop heads to its first public release and needs (a) a defined, operator-triggered release process, and (b) a decision on how the `qnop-ui` SPA reaches production — until now the Docker image was backend-only and the SPA existed solely behind the Vite dev server. The sibling plugwerk project has a proven release machinery (VERSION-file driven, two-workflow chain, multi-arch images) that we adapt rather than reinvent.

## Decision

### 1. The SPA ships inside the boot jar

The release artifact is **one container image** containing the Spring Boot jar with the built `qnop-ui` bundle embedded under `static/`. Single-origin serving matches the existing security design (same-site refresh cookie, CSRF cookie, OIDC redirect flow, CORS default of a single origin) and keeps operations to one process.

- Embedding is opt-in via the Gradle property `-PembedUi`: `:qnop-app:processResources` then depends on a `:qnop-app:buildUi` task that runs `pnpm install --frozen-lockfile && pnpm build` in `qnop-ui/` and copies `dist/` into the jar. Developer builds and the Testcontainers suite stay Node-free and fast.
- Client-side routes fall back to `index.html` through a resource resolver that never swallows `/api/**` or `/actuator/**` (API 404s stay 404s).
- The strict API CSP (`default-src 'none'`) now applies to `/api/**` and `/actuator/**` only; SPA responses carry a browser-app CSP (`'self'` sources, inline styles for MUI, blob workers for pdf.js).
- The CI smoke image (root `Dockerfile`) intentionally stays API-only — the release workflow is what builds and verifies the embedded artifact.

### 2. Release flow (plugwerk-style, VERSION-file driven)

- **`prepare-release.yml`** (`workflow_dispatch`): the operator enters `release_version` and `next_version` (`-SNAPSHOT`). The job verifies the actor holds admin/maintain, validates inputs (semver, tag free, `VERSION` currently a snapshot, branch `main`), then commits `VERSION=release`, tags `v<release>`, commits `VERSION=next-snapshot`, pushes, and watches the triggered release run. It authenticates with the **`RELEASE_TOKEN`** secret (fine-grained PAT, contents:write) because tags pushed with the default `GITHUB_TOKEN` do not trigger downstream workflows.
- **`release.yml`** (`on: push: tags: v*`): rejects `-SNAPSHOT` tags, verifies the tag matches `VERSION`, builds `:qnop-app:bootJar -PembedUi`, and publishes a **multi-arch image (linux/amd64 + linux/arm64)** to **GHCR** as `ghcr.io/qnophq/qnop-ce` with semver tags (`{version}`, `{major}.{minor}`, `latest`) and OCI labels (`licenses=AGPL-3.0-only`, source, title, description). It then creates the GitHub Release with `gh release create --generate-notes`.
- **Snapshot images from `main`** (`ci.yml`, job `snapshot-image`): every push to `main` whose CI gates pass additionally publishes the same embedded-SPA artifact to GHCR under the **moving `main` tag** plus an immutable `sha-*` tag. `latest` and semver tags stay reserved for tag-driven releases.
- **Docker Hub (opt-in)**: when the `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` secrets are configured, releases additionally publish `docker.io/qnophq/qnop-ce` in the same build and sync the Docker Hub README from `deploy/dockerhub-readme.md` (plugwerk-style). Without the secrets the release stays GHCR-only and emits a notice instead of failing. Snapshots stay GHCR-only either way.
- **Image naming: `qnop-ce` / `qnop-ee`.** The Community image is published as **`qnop-ce`**; the commercial edition (built from the private qnop-enterprise repo, ADR-0002/0003) will publish as **`qnop-ee`**. The explicit edition suffix keeps the pairing unambiguous once both exist (GitLab-style CE/EE). Repository, Gradle artifacts and the boot jar keep the plain `qnop` name — the edition is an attribute of the published container, and the running server reports it via `GET /api/v1/config`.
- **No CHANGELOG file.** Conventional-Commit PRs make generated release notes accurate; the GitHub Release is the changelog.
- The published image builds from the **copy-only** `deploy/Dockerfile` (jar staged by CI); building from source stays possible via the root `Dockerfile`.

### 3. Deployer surface

`deploy/docker-compose.yml` runs the published image with Postgres and MinIO, pinned via `QNOP_VERSION`; `docs/DEPLOYMENT.md` documents the environment contract (required secrets fail fast per ADR-0022), health endpoint, admin bootstrap and upgrade notes. The server enables graceful shutdown and env-driven forwarded-headers handling for reverse proxies.

## Consequences

- Releasing is a one-click, permission-gated operation; every release is reproducible from a tag.
- One image serves API + UI; deployments that want a separate frontend can still build `qnop-ui` themselves (the embedding is additive, not exclusive).
- `RELEASE_TOKEN` must exist as a repository secret before the first release; `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` are optional and activate the Docker Hub publication when added.
- Maven publication of `qnop-spi`/`qnop-api` (needed by qnop-enterprise) is deliberately out of scope here — tracked in #497; a rolling snapshot workflow may follow later.

## Amendment (2026-11, #913): nonce-based styles, no `'unsafe-inline'`

The SPA policy above allowed inline styles (`style-src 'self' 'unsafe-inline'`) because MUI's Emotion injects `<style>` elements at runtime. That allowance applied to every `<style>` element and every `style="…"` attribute an attacker could get into the document, so it is replaced by a per-request nonce.

**Policy.** SPA responses now carry `style-src 'self' 'nonce-<n>'` and no `'unsafe-inline'` anywhere — no `style-src-elem`/`style-src-attr` split either. A `style` attribute in markup is therefore not applied; style writes through the CSSOM (React's `style` prop, `element.style.x = …`, Emotion's `insertRule`, constructable stylesheets) are not governed by CSP and are unaffected. `script-src` stays `'self'` (the bundle has no inline script). API/actuator responses keep the deny-all policy.

**Nonce.** 128 bits from `SecureRandom`, standard Base64, minted once per request (`CspNonce.forRequest`) and shared by the two consumers that need it: `PathAwareCspHeaderWriter` (header) and `SpaIndexNonceTransformer` (body). Whichever runs first mints it.

**Per-request `index.html`.** The build leaves a `__CSP_NONCE__` placeholder (Vite `html.cspNonce`) on a `<meta property="csp-nonce" nonce="…">` and on the document's own `<script>`/`<link>` tags. The SPA resource handler substitutes the request's nonce through a resource transformer that only touches the bundle's root `index.html` (matched by location, not file name), with the resource chain's caching switched off (a cached transform would freeze one nonce; a cached resolution would also grow with every distinct client-side route). `/` forwards to `index.html` explicitly. Caching follows the kind of file: `index.html` and every client route are `no-store` without `Last-Modified`; Vite's content-hashed `/assets/**` are `public, max-age=31536000, immutable`; the un-hashed root files from `public/` (favicons, og-image) get `public, max-age=3600`. A missing chunk under `/assets/` and anything under `/t/` stay 404s instead of falling back to `index.html`. `qnop.ui.static-location` (default `classpath:/static/`) moves the bundle — a test seam, so only the one integration test that needs a stand-in bundle gets one.

**Client.** `main.tsx` wraps the app in an Emotion `CacheProvider` whose cache (`key: 'css'`) carries the nonce read from the meta tag (the `nonce` IDL property — browsers hide the attribute once a policy is in force). The audit of runtime `<style>` injection in the bundle found:

- **CodeMirror** (mail template editor) mounts its theme as a `<style>` in the document → `EditorView.cspNonce`.
- **react-easy-crop** (avatar crop) injects its stylesheet → its `nonce` prop.
- **emoji-mart 5** puts a fixed stylesheet into the picker's shadow root with no nonce support. It is built while the custom element is still detached, and CSP checks a `<style>` when it is connected, so the picker host stamps the page nonce on that element before attaching it. Chosen over a hash allowance (breaks silently on every emoji-mart upgrade and would be a second, static allowance next to the nonce) and over replacing the picker (disproportionate).
- **pdf.js** loads fonts through the Font Loading API and constructable stylesheets in every supported browser; its `<style>` fallback is only reached without them. qnop renders canvas only (no text/annotation layer).
- MUI's own `<style>` paths outside Emotion (`enableCssLayer`, `disableTransitionOnChange`) are not used.

**No implicit opt-out.** The header writer used to skip responses that already had a CSP header. It now always sets its policy and replaces whatever a handler set; no handler sets one today. A surface that genuinely needs a different policy gets its own branch in the writer, not an opt-out.

**Parity.** `vite dev` enforces `style-src 'self' 'nonce-…'` with a per-start nonce; `vite preview` — what the Playwright suite drives — serves `index.html` per request with the full SPA policy and a fresh nonce, mirroring the backend. Both serve the same policy string: the preview's copy (`src/utils/spaCspPolicy.ts`) is checked against the Java constant by a unit test. The Playwright page fixture records `securitypolicyviolation` events (directive and blocked source) plus CSP console errors, and fails the test that caused them.

**Rendered content.** Comment and annotation Markdown is rendered by react-markdown as React elements (no raw HTML, no `rehype-raw`) and sanitised with rehype-sanitize's default schema, which allows no `style` attribute; highlighting uses classes. Branding logos are `<img>` elements, so an SVG's own styles live in its image document and are not subject to the page's policy.
