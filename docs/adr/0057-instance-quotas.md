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

`qnop.features.oidc`, `.annotation-export`, `.custom-branding` (issue #674), `.scheduler-manual-run` (#676), `.scheduler-job-settings` (#677), `.smtp-configuration` (#678), `.email-templates` (#679), `.usage-tracking`, `.upload-constraints`, `.self-registration` (#681) and `.deployment-configuration` (#683), all defaulting to on. Their own prefix, because a switch is not a ceiling — the same block of a configuration file, because an operator reads them together.

The fourth is a different kind of thing from the first three and belongs here anyway. Run-now on a maintenance job is not a plan feature; it is an override that starts a sweep **regardless of the job's own enabled flag**. On a self-hosted installation that is exactly what an administrator needs. On a hosted one it is arbitrary load on demand, held by the person the operator cannot overrule. What is withheld is the trigger, never the schedule: the sweeps keep running on their cron, which is the property the tests pin down.

The fifth covers the jobs' own settings, and covers **both** of them with one switch. Dry-run is not a lesser setting beside the enabled flag: for the storage reaper and the review purge it means *runs but deletes nothing*, which is being off by another name. A switch that guarded `enabled` and left dry-run open would be a locked door beside an open window, so `scheduler-job-settings` governs the whole update endpoint. Two separate switches were considered and rejected: the only combination anybody would set is both.

The mail pair (#678/#679) is the first that had to be **selective inside an endpoint**. `PATCH /admin/settings` carries every application setting, so withholding the mail server cannot mean refusing the endpoint — it means refusing the `smtp.*` keys and leaving the rest editable. A patch that mixes them is refused whole rather than partly applied: silently dropping the keys the caller may not set would report success for a change that did not happen. Which keys those are is a question the registry answers (`ApplicationSettingKey.isSmtpConfiguration`), so a new `smtp.*` setting joins the group by being declared rather than by somebody remembering to widen a condition.

Both mail switches stop at the boundary of *changing* things. Reading a setting and previewing a template stay open, because they only show what the deployment already sends; the test-mail endpoints do not, because they send from the operator's server. And the guard for those sits in the controller rather than in `MailService` — every notification in the product goes through that service, and a switch about an admin screen has no business standing in their way.

### Settings-governing switches (#681)

Three more capabilities govern *groups of settings* rather than endpoints, so the selective guard became the general mechanism instead of a second special case. Each protected key names the capability that governs it (`ApplicationSettingKey.governedBy`); the write guard and the `editable` flag the API reports both come from that one lookup, which is why the form and the endpoint cannot disagree about what may be changed.

The guard runs **before validation**. Otherwise a governed field answers 400 or 403 depending on whether the value the caller was never allowed to set happened to be well-formed, and "you cannot change this" does not depend on the value.

What differs between the three is the effect, and each difference was chosen rather than inherited:

- **Usage tracking** withholds *changing* the configuration, not tracking. A configuration already in the database keeps working — an operator who set tracking up for a tenant wants it to run, not to be re-pointed. The section is dropped from the response entirely rather than greyed out, because an endpoint an administrator cannot change tells them nothing they can act on.
- **Upload constraints** stay visible and go read-only. The ceiling is the answer to "why was my file refused", which an administrator needs whether or not they can raise it.
- **Self-registration** is the only one that withholds the capability itself: off means nobody can sign up whatever the setting says. Because two places ask — the registration endpoint and the public config that decides whether a sign-up link appears — the effective answer lives in one method. Two independent `&&`s would have been one refactor away from a deployment that hides the link and still accepts registrations.

The configuration screen (#683) is the same distinction at the level of a whole page: `/admin/configuration` exists to report how the operator set this installation up, which on a managed instance is not the tenant's business. Its guard sits in the controller rather than in the services, and that is not a shortcut — `InstanceLimitService` enforces the quotas on every user and team that gets created, so a switch about an administration screen must never be able to reach it. The integration test withholds the screen and creates a user over the ceiling in the same breath: refused view, enforced quota.

So "governed" means *not editable here*, and deliberately not *inactive*. Conflating the two would have turned a configuration switch into an off switch for a feature the operator had chosen to run.

**Off closes the door rather than hiding the handle.** Removing an option from a list a client renders is not withholding a capability: `/oauth2/authorization/{id}` is a URL somebody can type, and an export endpoint answers on its own. So the OAuth2 filter chain is not registered at all when SSO is off, the export endpoint refuses as well as vanishing from the format list, and branding refuses uploads *and* serves the bundled logo for slots that already hold one — otherwise a downgrade would change nothing anybody can see.

They answer **403**, not the quotas' 409: nothing is full, and no amount of waiting changes it. Not 404 either — pretending the capability does not exist sends an administrator hunting for a setting instead of asking whoever operates the deployment.

A trap worth recording, because it cost a debugging round and would have shipped silently: the properties record carried a convenience no-argument constructor, and Spring's constructor binding chose *it* over the canonical one. Every configured `false` was ignored and the capabilities came back on — the precise opposite of what switching them off asks for. Defaults belong on the components (`@DefaultValue`), and a record used for configuration binding gets exactly one constructor.

### Visible before they bite

`GET /admin/limits` reports each quota with its usage, shown on the Configuration page — the surface that already answers "what did the deployment set" rather than "what did an administrator choose". An administrator should know they are at 24 of 25 before the twenty-sixth attempt fails.

`/config` publishes the first three capability flags, since a client needs them before anybody has logged in. The scheduler switch travels on the admin scheduler response instead: it answers a question only an administrator asks, that response is what the screen already loads, and a public endpoint is the wrong place to describe a deployment's internal operations. Same switch, different audience — so a different messenger.

**A withheld page refuses at its route, not only in the menu (#682).** The first version dropped the sidebar entry and left the destination standing, so `/admin/oidc-providers` typed into the address bar still rendered a working-looking screen whose every action the server refused. That is the export-button mistake again, one level up: "the endpoint refuses anyway" justifies removing the menu entry, it does not justify leaving the destination. Those routes now leave for a full-page error — its own page, not the 403, because that one says *your account* has no access and no role changes this answer. It lives outside the shell beside the other branded error routes (ADR-0042 / issue #611), and that placement is the point: rendered inside the shell it sat under the sidebar and, on the mail routes, under the Email header and its tab strip, which framed a wall as a panel that had failed to load. The cost is the address bar, which says `/feature-unavailable` rather than the page that was asked for — accepted, because a refusal that looks like a broken page is the worse trade. Nothing renders while the config is in flight, and a config that failed to load shows the page: not knowing is not evidence, and the endpoints still answer.

The client also drops what a withheld capability would offer: the sidebar omits the pages that administer it, and the export button does not appear on a review. That is presentation, not enforcement — the endpoints refuse either way — but leaving it out was a real defect, not a cosmetic one: the first version hid the pages and kept the button, so a user could configure an export through four steps of a wizard and be refused at the download. **An affordance that cannot succeed is worse than no affordance**, and "the endpoint refuses anyway" is not a reason to leave one standing.

Where the *whole* capability is gone, the affordance goes silently — there is nothing left to explain on a page the user can no longer reach. Where only one action is withheld and the page remains useful, the page says so once, at the top: the scheduler still shows schedules, dry-run and enable/disable, and run buttons that had simply vanished would read as a broken scheduler rather than a deliberate setting. The sentence names the deployment, not the user's permissions, so the reader takes the question to whoever operates it. Where two switches are off at once it stays one sentence, because the operator made one decision.

Read-only is not the same call as removed, and the difference is whether the control carries information. A button that cannot be pressed says nothing, so it goes. A switch says whether the job is on — worth reading even when it cannot be flipped — so it stays and greys out. Same principle, opposite outcome; getting this backwards is how a page ends up either lying or losing its state display.

## Consequences

**Positive.** A hosted plan is enforceable without touching the data model, and without a tenant being able to lift their own ceiling. Community behaviour is unchanged and costs nothing. The per-team quota reports the fullest team, which is the number that answers "will the next invitation land".

**Negative.** A plan change requires a restart, for capabilities as much as for quotas. The quotas are instance-wide, so they suit one-tenant-per-deployment (which is what qnop SaaS is) and would need rework for several tenants in one instance. The race window above is real, if small.

**Neutral.** Freeing a user seat means deleting an account, not disabling one (see the correction below), so a tenant at its ceiling has to choose between the audit trail and the seat.

### Correction: a seat is a record (#687)

The first version counted **enabled** accounts, on the reasoning that deactivating a leaver should not cost a seat. A live deployment configured for 30 was found holding 31: thirty enabled and one disabled. The guard had worked exactly as written.

That reading is wrong for a quota, on two counts. It is not what an operator means by "thirty users", and it is avoidable — disable an account, create another, repeat. A ceiling somebody can step over by pressing a toggle is not a ceiling. So the count is now every account.

The cost is real and accepted: a tenant that deactivates leavers rather than deleting them fills up, and has to delete history to make room. That is the trade an operator can price; the alternative was a quota that does not hold.

The same round added the missing half of the feature. Nothing had warned before the refusal — the ceiling was only visible on `/admin/configuration`, which a deployment may withhold (#683), so an administrator could fill in a whole form and be refused at submit. `GET /admin/users` now carries `seatLimit` and `seatsUsed`, and *Add user* is disabled with the number on screen. A quota that is only ever discovered by hitting it is a quota that reads as a bug — which is precisely how this one was reported.

## Related

- **ADR-0025** — application settings, and why these are deliberately not among them
- **ADR-0011** — the extensible workflow state that made a negative definition of "active" necessary
- **ADR-0012 / 0039** — edition and entitlements; a later licence-driven source for these numbers would replace where they come from, not what they mean
- **Issue #666** — the same reasoning for a deployment-only property (`allow-private-host`)
