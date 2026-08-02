# ADR-0057: Instance quotas — deployment properties, enforced at creation

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** devtank42 (with Claude)

## Context

qnop SaaS needs a way to bound what one instance holds: users, teams, members of a team, and reviews being worked on. Today nothing stops a deployment from growing arbitrarily, which is fine for a self-hosted Community installation and not fine for a hosted plan.

The awkward part is not the counting. It is that the person who must not be able to change a quota — the administrator of the instance — is exactly the person qnop otherwise trusts with every global setting.

## Decision

### Deployment properties, not application settings

`qnop.limits.*` in the deployment's configuration, alongside `qnop.tracking.allow-private-host` (ADR-0056) and for the same reason: a decision only somebody with deployment access may make does not belong in a web form. Application settings (ADR-0025) are administered through `/admin/settings`, and a quota its own subject can edit is a suggestion.

The cost is real and accepted: changing a customer's plan is a restart with new values, not a click. For a hosted deployment that is a routine operation; for the alternative — a tenant raising their own ceiling — there is no operational fix at all.

`0` means unlimited, so a Community deployment is untouched and an unconfigured quota costs nothing: the check returns before it counts anything.

### Enforced where records are created, never retroactively

Each quota is checked immediately before the record it bounds is written, by throwing rather than returning a flag — a caller cannot forget to inspect a value that does not exist.

Lowering a quota below current usage does **not** disable anything. It refuses the next record and leaves every existing one alone. The alternative would mean a deployment that deactivates accounts an operator is still working with, which is a worse failure than being over a limit.

Users are created on three paths and all three are guarded: an administrator adding somebody, self-registration, and first login through an identity provider. A quota the OIDC path walks around is not a quota, and that path is the easy one to forget.

### "Active" is *not closed and not archived*

The review quota counts what is being worked on. It is expressed as a negative rather than as a list of open states, because `workflow_state` is deliberately an extensible string (ADR-0011) that enterprise builds add to. Naming the open states would have counted an enterprise state — a review awaiting signature (ADR-0035) — as finished, freeing a seat that is still in use. `closed_at` is set for exactly the terminal states, so it says "finished" without having to know their names.

Finished work therefore occupies nothing: a tenant with fifty finalized reviews still has their whole quota.

### 409, with the number in it

A full quota is not a permission problem and not a malformed request: the caller was entitled to do this and asked correctly, and the deployment has no room. That is a conflict with current state. The distinction matters to whoever has to act — a permission problem is solved by an administrator, a full quota by whoever operates the deployment — so each quota has its own code (`USER_LIMIT_EXCEEDED` and friends) and the message carries the ceiling. "Refused" without a number leaves the reader guessing at what to free.

### Checked, not serialised

Two administrators creating the last permitted user at the same moment can both pass the check. Closing that would mean serialising every creation against a lock: a cost on every request to prevent an overshoot of one, on operations nobody performs twice a second.

Quotas here are a commercial boundary, not a safety interlock. This is written down rather than left to be discovered by whoever first reads the code and wonders.

### Visible before they bite

`GET /admin/limits` reports each quota with its usage, shown on the Configuration page — the surface that already shows what the deployment set rather than what an administrator chose. An administrator should know they are at 24 of 25 before the twenty-sixth attempt fails.

## Consequences

**Positive.** A hosted plan is enforceable without touching the data model, and without a tenant being able to lift their own ceiling. Community behaviour is unchanged and costs nothing. The per-team quota reports the fullest team, which is the number that answers "will the next invitation land".

**Negative.** A plan change requires a restart. The quotas are instance-wide, so they suit one-tenant-per-deployment (which is what qnop SaaS is) and would need rework for several tenants in one instance. The race window above is real, if small.

**Neutral.** Disabled accounts do not occupy a seat, making "deactivate the leaver" cheaper than deleting their history — which is the behaviour an audit trail wants anyway.

## Related

- **ADR-0025** — application settings, and why these are deliberately not among them
- **ADR-0011** — the extensible workflow state that made a negative definition of "active" necessary
- **ADR-0012 / 0039** — edition and entitlements; a later licence-driven source for these numbers would replace where they come from, not what they mean
- **Issue #666** — the same reasoning for a deployment-only property (`allow-private-host`)
