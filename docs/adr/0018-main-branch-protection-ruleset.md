# ADR-0018: main-branch protection via a repository ruleset (deferred enforcement)

- **Status:** Proposed
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

ADR-0008 and `CONTRIBUTING.md` make `main` integration-only: no direct commits, all changes via PR. So far this is convention-only. We want it **enforced by the platform** so the rule cannot be bypassed by mistake.

Two constraints shaped the decision:

1. **GitHub rulesets cannot target individual users** as bypass actors — only roles, teams, GitHub Apps, or "Organization/Repository admin". Both maintainers (`bigpuritz`, `devtank42`) are **org owners**, so the `OrganizationAdmin` actor covers exactly the intended two people without creating a team (which would need `admin:org`).
2. **`qnophq/qnop` is private on the GitHub Free plan**, where rulesets/branch protection are unavailable (the API returns `403: "Upgrade to GitHub Pro or make this repository public"`). `plugwerk/plugwerk` is public, which is why the same setup works there for free.

## Decision

Adopt a **repository ruleset** named `main-branch-protection` on `refs/heads/main`:

- **Pull-request rule:** 1 approving review, dismiss stale approvals on push, all merge methods allowed.
- **Block deletion** and **block force-push** (`non_fast_forward`).
- **Bypass:** `OrganizationAdmin` with mode **`pull_request`** — the two owners may merge PRs that bypass the review requirement, but **nobody (not even owners) may push directly to `main`**. This is stricter than "owner may direct-commit" and matches the CLAUDE.md / ADR-0008 rule that everything lands via PR.

**Enforcement is deferred** until one of: (a) `qnophq/qnop` becomes public, or (b) the `qnophq` org upgrades to GitHub Team. The ruleset definition below is the source of truth; apply it unchanged once the plan/visibility allows.

```bash
gh api --method POST repos/qnophq/qnop/rulesets \
  --input - <<'JSON'
{
  "name": "main-branch-protection",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/heads/main"], "exclude": [] } },
  "rules": [
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["squash", "merge", "rebase"]
    }},
    { "type": "deletion" },
    { "type": "non_fast_forward" }
  ],
  "bypass_actors": [
    { "actor_id": 1, "actor_type": "OrganizationAdmin", "bypass_mode": "pull_request" }
  ]
}
JSON
```

## Consequences

- Once enforced: `main` is tamper-resistant; PRs are mandatory; the two owners retain a merge bypass for solo/urgent work but still cannot force-push or delete `main`.
- Until enforced: the rule remains convention-only (ADR-0008). This ADR stays **Proposed** until the ruleset is live, then flips to **Accepted**.
- If a future contributor must merge but is not an org owner, grant them the role via a team-based bypass (needs `admin:org`) rather than widening `OrganizationAdmin`.

## Alternatives considered

- **Make the repo public now** to unlock free rulesets. Rejected for now — qnop is not ready for public release; revisit at launch.
- **Upgrade the org to GitHub Team** purely for branch protection. Deferred — not worth a paid plan at this stage; the convention plus the merged-PR workflow is sufficient for a two-owner team.
- **Bypass mode `always`** (owners may direct-push). Rejected — contradicts the "everything via PR" rule; `pull_request` mode keeps history honest.
- **Per-user / team bypass split** (owner direct-push, second owner PR-only). Rejected — needs `admin:org` and two teams for marginal value at this team size.
