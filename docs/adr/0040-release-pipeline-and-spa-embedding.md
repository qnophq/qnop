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
