# ADR-0016: Contributor License Agreement enforced via CLA Assistant

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

qnop is open-core (ADR-0002): the Community edition is AGPL-3.0, while commercial add-ons and a possible SaaS are distributed under separate commercial terms. To relicense contributed code under those commercial terms, the project needs each contributor to grant a sublicensing right — the inbound AGPL license alone does not permit the Licensor to offer the same code under a proprietary license.

Contributions may also come from AI coding agents operated by a human. We need an unambiguous record that the human operator, not the agent, is the accountable contributor.

We want enforcement to be automatic, auditable, and to live in the repo as code — not a manual checklist a maintainer has to remember.

## Decision

Require every contributor to sign a **Contributor License Agreement** before their first PR can be merged.

- `CLA.md` (repo root) states the grant: a perpetual, irrevocable copyright license **with the right to sublicense under any terms, including commercial**, plus a patent grant, representations, a corporate-contributor clause, and an explicit AI-agent clause (the human operator signs).
- `.github/workflows/cla.yml` runs [`contributor-assistant/github-action`](https://github.com/contributor-assistant/github-action), pinned to a full commit SHA. Signatures are recorded in `signatures/cla.json` on a dedicated `cla-signatures` branch. The signing phrase is *"I have read the CLA Document and I hereby sign the CLA"*.
- Allowlist: `bigpuritz,devtank42,*[bot]` (maintainers and bots are exempt). AI co-authors added via a `Co-Authored-By:` trailer (e.g. `Claude <noreply@anthropic.com>`) are **not** asked to sign — they have no GitHub login and, per CLA §8, the human operator signs, not the agent.
- **One-time bootstrap (required):** the `cla-signatures` branch must already exist and be **unprotected**, holding an initialized `signatures/cla.json` (`{"signedContributors": []}`). The action does **not** create a non-default branch — without it the first run fails with *"Branch cla-signatures not found"*. (Bootstrapped as an orphan branch; the action then creates/updates the file itself.)
- A repo secret **`CLA_TOKEN`** (PAT) is **optional** — useful for storing signatures in a remote repo or for extra robustness. The default `GITHUB_TOKEN` (with the job's `contents`/`pull-requests` write scopes) is sufficient for same-repo signature storage; verified green with no PAT set.

### Security model (the reason this ADR exists)

The workflow triggers on `pull_request_target` + `issue_comment`, which run in the **base-repo context with access to secrets**, even for fork PRs. This is the highest-risk workflow in the repo. Three hardenings are mandatory and must not be removed:

1. **SHA-pinned action.** Never a floating tag. Renovate (group "GitHub Actions") proposes updates.
2. **Job-level permissions.** Workflow-level `permissions: {}` drops all defaults; the write scopes (`actions`, `contents`, `pull-requests`, `statuses`) live on the `cla-check` job only, so future jobs do not inherit them.
3. **Never check out PR head.** `pull_request_target` + `actions/checkout` of the fork head + secrets is the classic supply-chain exploit. Code-building/linting of PRs happens in separate `pull_request` workflows (fork-safe, no secrets).

The `issue_comment` trigger is filtered to PR comments only (`github.event.issue.pull_request != null`); no untrusted event payload is interpolated into any shell.

## Consequences

- Easier: the project can legally offer commercial editions of contributed code; provenance is auditable in `signatures/cla.json`.
- Harder: first-time external contributors must take one extra step (post the signing comment). The bot guides them automatically.
- One-time setup: the unprotected `cla-signatures` branch with an initialized `signatures/cla.json` must exist before the first run (the action does not auto-create it). No secret is strictly required — `GITHUB_TOKEN` suffices; `CLA_TOKEN` stays optional.
- The CLA is referenced from `CONTRIBUTING.md` (ADR-0008 workflow).

## Alternatives considered

- **DCO only (no CLA).** Already in force (ADR-0007) and kept — but the DCO certifies origin under the *inbound* license; it grants no sublicensing right, so it cannot support commercial relicensing. CLA and DCO are complementary, not alternatives.
- **No agreement.** Would force every commercial use to obtain per-contributor permission retroactively — unworkable for open-core.
- **Self-hosted CLA bot / CLA-assistant.io.** More moving parts and an external service; the GitHub Action keeps enforcement in-repo with no extra hosting.
