# Contributing to qnop

Thanks for contributing! qnop is the AGPL-3.0 Community core of an open-core product. This guide is binding for all contributors — humans and AI agents alike. The rationale is recorded in [ADR-0008](docs/adr/0008-contribution-and-branching-workflow.md).

## Workflow

1. **Open an issue first.** Describe the intent before changing code.
2. **Branch off `main`.** Use `feat/…`, `fix/…`, `docs/…`, `chore/…`, `refactor/…`, `test/…`.
3. **Never commit or push directly to `main`.** `main` is integration-only and protected; all changes land via Pull Request.
4. **Open a PR** that references its issue (e.g. `Closes #12`). Keep PRs focused.
5. CI must be green (backend build + ArchUnit + Spotless, frontend lint/build, SPDX check) before merge.

## Commits

Conventional Commits:

```
<type>: <subject>

<optional body>

Signed-off-by: Your Name <you@example.com>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`.

### Developer Certificate of Origin (DCO)

Sign off every commit (`git commit -s`) to certify you have the right to submit it under the project license.

### Claude co-authorship

Work produced with Claude must be attributed:

- **Commits** — add a trailer:
  ```
  Co-Authored-By: Claude <noreply@anthropic.com>
  ```
- **Issues / PRs** — add an attribution line in the body, e.g.
  `🤖 Mitarbeit: Claude (Opus 4.x) via Claude Code`.

## Code quality

- **Backend**: `./gradlew spotlessApply` before committing; `./gradlew build` must pass (runs Spotless check + ArchUnit). Every source file needs an `SPDX-License-Identifier: AGPL-3.0-only` header.
- **Frontend**: `pnpm format && pnpm lint && pnpm build` from `frontend/`.
- Respect the layered boundaries ([ADR-0004](docs/adr/0004-layered-architecture-enforced-by-archunit.md)) — `web → service → repository → entity`; the published contracts (`qnop-spi`, `qnop-api`) stay Spring-free.

## Architecture decisions

Significant decisions are recorded as [ADRs](docs/adr/README.md). If your change involves one, add an ADR in the same PR.

## License & dependencies

By contributing you agree your work is licensed under AGPL-3.0. Prefer permissive dependencies (Apache-2.0/MIT/BSD/MPL-2.0); copyleft tools may only be used out-of-process. See [ADR-0007](docs/adr/0007-spdx-dco-license-scanning.md).
