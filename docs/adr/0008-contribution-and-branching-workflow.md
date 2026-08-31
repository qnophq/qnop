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

## Amendment (2026-07-16, branch types & attribution form)

Two alignments with the repo working rules (`CLAUDE.md`):

- **Branch types** follow [Conventional Branch](https://conventionalbranch.org/): the set is `{feat, fix, hotfix, release, chore}` (`feat`/`fix` being the accepted short forms of `feature`/`bugfix`), lowercase + hyphens, optional issue number (e.g. `feat/issue-123-new-login`). The `docs/…` prefix sketched in rule 3 is not part of the set; documentation changes ride `chore/…` or `feat/…` branches.
- **Attribution is English throughout:** commits carry `Co-Authored-By: Claude <noreply@anthropic.com>`; issues and PRs carry `🤖 Co-Author: Claude (Opus 4.x) via Claude Code` (replacing the German `🤖 Mitarbeit: …` example in rule 4).

## Amendment (2026-08-31, issue #793: immediate push + independent agent review)

The approval-gated push in the repo working rules (`CLAUDE.md` rule 10 — "push only with explicit approval") is replaced by an immediate-flow rule:

1. **Push + PR immediately.** When a change is complete and the local gates are green, the feature branch is pushed and its PR opened right away — no per-conversation approval. Rule 2 (never push to `main`) is untouched; destructive rewrites of already-pushed history still require explicit approval.
2. **Independent agent review.** After the PR is opened, a completely fresh reviewer agent — sharing no context with the authoring agent — reviews the PR, evaluates the code and leaves its findings as PR comments (severity-classified per the code-review standards).
3. **The author addresses the review.** The original authoring agent works through the reviewer's comments and resolves or rebuts each one on the PR.

This strengthens rule 3's review guarantee: every PR now receives a mandatory independent review pass instead of an optional human one, and turnaround shortens because pushing no longer waits on a human in the loop.
