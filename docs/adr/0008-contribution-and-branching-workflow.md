# ADR-0008: Contribution & branching workflow

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

The project requires a disciplined, traceable change process from day one: every change should be tracked, reviewable, and attributable, and `main` must always reflect reviewed work.

## Decision

The following rules are binding for all contributors (human and AI agents):

1. **Issue first.** Every change starts with a GitHub issue describing the intent.
2. **No direct commits or pushes to `main`.** `main` is integration-only and should be protected.
3. **Feature branch → Pull Request.** All changes land via a short-lived branch (`feat/…`, `fix/…`, `docs/…`, `chore/…`) and a PR that references its issue.
4. **Claude co-authorship attribution.** Work produced with Claude carries attribution:
   - **Commits**: a `Co-Authored-By: Claude <…>` trailer.
   - **Issues / PRs**: an attribution line in the body (e.g. `🤖 Mitarbeit: Claude … via Claude Code`).
5. **Important architecture decisions are recorded as ADRs** ([ADR-0001](0001-record-architecture-decisions.md)).

Commit messages follow Conventional Commits (`type: subject`).

## Consequences

- Full traceability: issue → branch → PR → commits, with clear authorship.
- `main` stays green and reviewed; history is auditable (important for the open-core/AGPL provenance, [ADR-0007](0007-spdx-dco-license-scanning.md)).
- Slightly more ceremony for tiny changes; accepted for the traceability guarantee.
- Branch protection on `main` should be enabled in repository settings to enforce rule 2 mechanically.

## Alternatives considered

- **Commit straight to `main` for small changes** — rejected: breaks traceability and the protected-branch guarantee.
- **Trunk-based with no PR** — rejected: loses mandatory review and the issue trail.
