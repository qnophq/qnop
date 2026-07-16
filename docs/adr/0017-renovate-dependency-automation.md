# ADR-0017: Dependency automation via self-hosted Renovate and an org-wide preset

- **Status:** Accepted (amended 2026-06-17 — see [Amendment](#amendment-2026-06-17-github-app-token-model))
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

qnop spans several dependency ecosystems: Gradle (Java 21, Spring Boot 4.x, Liquibase, AWS SDK, PDFBox/POI), the Gradle wrapper, GitHub Actions, Docker images (`docker-compose.yml`), and an npm/pnpm frontend (React 19, MUI, Vite, TypeScript). Keeping these current by hand does not scale and tends to let security patches rot.

We also expect more repos under the `qnophq` org over time (e.g. the private `qnop-enterprise` repo, examples, a website). Update policy (grouping, scheduling, digest pinning) should be defined once, not copy-pasted per repo.

We do not want to grant a third-party SaaS (the Mend-hosted Renovate GitHub App) write access across the org.

## Decision

Run **our own Renovate** via GitHub Actions, configured through an **org-wide preset**.

1. **Org preset** lives in the public repo **`qnophq/.github`** as `default.json`. Repos opt in with `extends: ["github>qnophq/.github"]`. It sets: weekday-morning schedule (Europe/Berlin), dependency dashboard, GitHub Actions + Docker **digest pinning**, grouped minor/patch updates, per-package major-update PRs, and exact npm version pinning (we ship applications, not libraries).
2. **Reusable workflow** in `qnophq/.github/.github/workflows/renovate.yml` runs `renovatebot/github-action` (SHA-pinned). Consumer repos add a short stub (`.github/workflows/renovate.yml`) that wires their cron + permissions and calls it. This is the "own/self-hosted instance" — no Mend app.
3. **Single-repo token model.** Each run uses the caller's repo-scoped `GITHUB_TOKEN` (`RENOVATE_REPOSITORIES = github.repository`). No PAT, no cross-repo write access. _(Superseded 2026-06-17 — Renovate now authenticates as a GitHub App; see the [Amendment](#amendment-2026-06-17-github-app-token-model). Still single-repo: the installation token is scoped to the calling repo.)_
4. **Repo-specific rules** for qnop live in `qnophq/qnop/.github/renovate.json`, which extends the org preset and adds Gradle/frontend package groupings.
5. A **`renovate-config-validator`** workflow in `qnophq/.github` fails the build if `default.json` is syntactically broken, so a bad org preset cannot silently disable updates org-wide.

`qnophq/.github` is **public** so Renovate can resolve the `github>qnophq/.github` preset with the repo-scoped token alone; the preset holds only policy, no secrets.

## Consequences

- Easier: one place to evolve org-wide update policy; every repo inherits it. Security patches surface as grouped PRs on a predictable cadence. No third-party app holds org write access.
- Harder: the reusable-workflow SHA and the org preset are cross-cutting — a breaking change there affects every consumer; the validator workflow mitigates this.
- The frontend currently has no committed lockfile; Renovate's npm manager still pins `package.json` ranges. A lockfile should be committed when the frontend stabilizes.
- GitHub Actions minutes are consumed on the private `qnop` repo per scheduled run (within the free quota at current cadence).

## Alternatives considered

- **Mend-hosted Renovate App.** Zero maintenance, but grants a third-party SaaS write access across the org and moves config out of our control. Rejected for an open-core project that will host a private commercial repo in the same org.
- **Dependabot.** Native and simple, but weaker grouping/scheduling, no shared org preset of this richness, and no `gradle-wrapper`/`docker-compose` parity with what we want.
- **Per-repo Renovate config with no org preset.** Duplicates policy across repos and drifts. Rejected in favor of `extends: github>qnophq/.github`.

## Amendment (2026-06-17): GitHub App token model

**Supersedes decision point 3 (single-repo `GITHUB_TOKEN`).**

The original `GITHUB_TOKEN` model could push branches but **never opened PRs** (issue #67). Two reasons:

1. The `qnophq` org enforces **"Allow GitHub Actions to create and approve pull requests = OFF"**, so `GITHUB_TOKEN` gets a 403 on `POST /pulls`. The repo-level toggle returns 409 — it cannot override the org policy.
2. Even with that enabled, PRs created by `GITHUB_TOKEN` do **not** trigger `pull_request` workflows (GitHub's anti-recursion rule), so Renovate PRs would carry no CI/CLA checks and be un-mergeable under the working rules (ADR-0008).

**Decision:** Renovate authenticates as an **org-installed GitHub App**. The reusable workflow mints a short-lived installation token via `actions/create-github-app-token` (SHA-pinned) and passes it to `renovatebot/github-action`. Each consumer repo:

- installs the App (repository permissions: Contents, Pull requests, Issues, **Workflows** — the last is needed because Renovate edits `.github/workflows/*` to bump pinned action digests);
- stores `RENOVATE_APP_ID` + `RENOVATE_APP_PRIVATE_KEY` as **repository** secrets (or an org secret scoped to *selected* private repos — never "All repositories", to keep the key off public org repos like `qnophq/.github`);
- adds `secrets: inherit` to its caller stub so the secrets reach the reusable workflow.

An App installation token is a distinct identity: it is not bound by the Actions-can't-create-PRs policy, and its PRs trigger consumer CI normally. The model stays **single-repo** — the installation token is scoped to the calling repo, preserving the "own/self-hosted instance, no cross-repo write" property. We still avoid the Mend-hosted SaaS App; this is our own App under org control.

**Consequence:** onboarding a new repo to Renovate now also requires installing the App and adding the two secrets, not just the workflow stub. The trade-off buys working PRs *with* CI, which the `GITHUB_TOKEN` model could not provide.

## Amendment (2026-07-16): frontend lockfile

The frontend lockfile noted as missing under *Consequences* (`qnop-ui/pnpm-lock.yaml`) has since been committed; Renovate updates it as part of its npm PRs.
