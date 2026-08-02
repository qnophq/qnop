# ADR-0057: Instance quotas and capability switches — deployment properties

- **Status:** Accepted
- **Date:** 2026-08-02
- **Deciders:** devtank42 (with Claude)

## Context

qnop SaaS needs a way to bound what one instance holds — users, teams, members of a team, reviews being worked on — and to withhold whole capabilities that belong to a higher plan: single sign-on, annotation export, custom branding. Today nothing stops a deployment from growing arbitrarily, which is fine for a self-hosted Community installation and not fine for a hosted plan.

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

### Capabilities are switches beside the quotas, not more of them

`qnop.features.oidc`, `.annotation-export` and `.custom-branding` (issue #674), all defaulting to on. Their own prefix, because a switch is not a ceiling — the same block of a configuration file, because an operator reads them together.

**Off closes the door rather than hiding the handle.** Removing an option from a list a client renders is not withholding a capability: `/oauth2/authorization/{id}` is a URL somebody can type, and an export endpoint answers on its own. So the OAuth2 filter chain is not registered at all when SSO is off, the export endpoint refuses as well as vanishing from the format list, and branding refuses uploads *and* serves the bundled logo for slots that already hold one — otherwise a downgrade would change nothing anybody can see.

They answer **403**, not the quotas' 409: nothing is full, and no amount of waiting changes it. Not 404 either — pretending the capability does not exist sends an administrator hunting for a setting instead of asking whoever operates the deployment.

A trap worth recording, because it cost a debugging round and would have shipped silently: the properties record carried a convenience no-argument constructor, and Spring's constructor binding chose *it* over the canonical one. Every configured `false` was ignored and the capabilities came back on — the precise opposite of what switching them off asks for. Defaults belong on the components (`@DefaultValue`), and a record used for configuration binding gets exactly one constructor.

### Visible before they bite

`GET /admin/limits` reports each quota with its usage, shown on the Configuration page — the surface that already answers "what did the deployment set" rather than "what did an administrator choose". An administrator should know they are at 24 of 25 before the twenty-sixth attempt fails.

`/config` publishes the capability flags alongside, and the sidebar drops the pages that administer a withheld capability. That is presentation, not enforcement: the endpoints refuse either way. A page whose every action would be denied is simply not worth offering.

## Consequences

**Positive.** A hosted plan is enforceable without touching the data model, and without a tenant being able to lift their own ceiling. Community behaviour is unchanged and costs nothing. The per-team quota reports the fullest team, which is the number that answers "will the next invitation land".

**Negative.** A plan change requires a restart, for capabilities as much as for quotas. The quotas are instance-wide, so they suit one-tenant-per-deployment (which is what qnop SaaS is) and would need rework for several tenants in one instance. The race window above is real, if small.

**Neutral.** Disabled accounts do not occupy a seat, making "deactivate the leaver" cheaper than deleting their history — which is the behaviour an audit trail wants anyway.

## Related

- **ADR-0025** — application settings, and why these are deliberately not among them
- **ADR-0011** — the extensible workflow state that made a negative definition of "active" necessary
- **ADR-0012 / 0039** — edition and entitlements; a later licence-driven source for these numbers would replace where they come from, not what they mean
- **Issue #666** — the same reasoning for a deployment-only property (`allow-private-host`)
