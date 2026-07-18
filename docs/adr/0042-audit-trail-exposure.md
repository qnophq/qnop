# ADR-0042: Audit-trail exposure — the AUDITOR read surface and its boundary

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** devtank42 (with Claude)

## Context

The `AuditEvent` append-only log (issue #244, ADR-0011) has recorded the full document-review trail since the review core shipped — `annotation.created/resolved/reopened`, `placement.confirmed/reattached`, `workflow.transition`, `document.due_date.changed`, and the extraction outcomes of #325. Until now it had exactly two readers, both **per-document** and both incidental to a review: the dashboard activity feed (issue #454 — the *caller's own* reviews, a personal glance) and the internal `countBy…` aggregate. There was no way to read the trail *across* documents.

Meanwhile the `AUDITOR` global role (ADR-0023, issue #98) has existed in the enum, the JWT claim, the DB `CHECK`, and the OpenAPI contract — documented as *"organisation-wide read access to the audit trail and compliance"* — but it gated **nothing**. An `AUDITOR` had byte-for-byte the same access as a `MEMBER`. The capability was a promise with no implementation (issue #466).

Two questions had to be answered before building the view:

1. **What may an `AUDITOR` read?** The whole organisation's trail, or only documents they participate in?
2. **Where is the boundary** between *this* audit surface and a future system-level audit (logins, password resets, settings/branding changes, user & team administration)?

A third, quieter force: the review core deliberately anonymises authorship **per review** (ADR-0038). An org-wide compliance view is the one place where that pseudonymisation must *not* apply — an auditor who sees "Participant 3" cannot audit anything.

## Decision

**1. `AUDITOR` (and `ADMIN`) read the entire organisation's document-review trail — org-wide, unfiltered by participation.** This is what "organisation-wide read access" means; a participation filter would make the role indistinguishable from `MEMBER` for most rows and defeat its purpose. The audit list is therefore *not* scoped through `findVisibleTo` the way `/documents` and the dashboard are — it queries `audit_event` directly.

**2. The audit view reports the *real* actor identity, bypassing per-review anonymity (ADR-0038).** Compliance is precisely the context ADR-0038's pseudonymisation is not meant to obstruct. Concretely, the service resolves actor ids through a **direct** `UserRepository.findDisplayNamesByIdIn` batch lookup — deliberately **not** through `ReviewIdentityResolver`, which is per-review and would re-pseudonymise. The system actor (`actorId == null`) renders as the literal **"System"**; an actor whose user row no longer exists resolves to `null` (never a raw UUID).

**3. `AUDITOR` becomes real as a path-based authorization gate, mirroring `ADMIN`.** A new matcher `"/api/v1/audit/**"` requires `hasAnyRole("AUDITOR", "ADMIN")`, placed before `anyRequest().authenticated()` in `SecurityConfiguration`, exactly as `/api/v1/admin/**` requires `ADMIN`. Because there is **no `RoleHierarchy` bean** (each user carries exactly one `ROLE_*` authority), `ADMIN` is named explicitly in the gate rather than inherited — `hasRole("AUDITOR")` alone would lock admins out. This is the first — and for now only — endpoint the `AUDITOR` role unlocks.

**4. The endpoint is a paginated, filterable, newest-first list**: `GET /audit/events` with optional `eventType`, `actorId`, `documentId`, and a `from`/`to` `createdAt` range, `page`/`size` bounded to a max of 100, ordered `createdAt DESC`. It follows the established list envelope (`items/total/page/size`, ADR-0021) and returns each event with the actor and document resolved to display names and the `detail` jsonb passed through as a raw JSON string for the client to render per event type.

**5. Boundary: this surface is the document-review trail only.** System-level events (authentication, password resets, application-settings and branding changes, user/team administration) are **not** written to `audit_event` today and are **explicitly out of scope**. A system audit is a distinct, larger effort — its own event stream (likely a separate table with a different scope key, since `audit_event.document_id` is `NOT NULL`), its own retention policy, and its own exposure decision. When it lands it will extend, not reshape, this view. Export (CSV/JSON), retention/rotation, and a participant-scoped per-document trail are noted follow-ups, not part of this decision.

## Consequences

- **Easier:** `AUDITOR` finally does something; compliance can browse the whole review trail with names, not UUIDs; the list endpoint reuses the existing pagination envelope, DTO-mapping, and identity-lookup building blocks, so it is thin.
- **Harder / accepted:** the audit query is intentionally **un-scoped** — it reads every document's events, so it must stay behind the role gate with no per-row visibility fallback. Anyone who can reach it sees everything. The `detail` payload ships as an opaque JSON string, pushing per-event-type rendering to the client (acceptable — the vocabulary is small and documented).
- **Deferred:** system-level audit, export, retention, and a per-document participant view. The `NOT NULL document_id` on `audit_event` is now also a *statement of scope*: this table is the review trail, and a system audit will not be shoehorned into it.
- **Privacy note:** this is the deliberate, documented exception to ADR-0038 — real identities are exposed here **only** behind the `AUDITOR`/`ADMIN` gate, and nowhere else.

## Alternatives considered

- **Participation-scoped audit (reuse `findVisibleTo`).** Rejected: it contradicts the role's definition and would surface almost nothing org-wide, making `AUDITOR` pointless.
- **Resolve identities through `ReviewIdentityResolver` for consistency with the rest of the review API.** Rejected: it would re-pseudonymise per review and blind the auditor — the opposite of the surface's purpose. The direct name lookup is the correct tool here.
- **`@PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")` on the controller.** Rejected for consistency: the codebase gates by path matchers in `SecurityConfiguration` (no `@PreAuthorize`/`@Secured` anywhere yet); introducing method security for one endpoint would fragment the authorization story.
- **Introduce a `RoleHierarchy` so `ADMIN` implies `AUDITOR`.** Rejected as out of scope and higher-blast-radius: it would silently widen every future `AUDITOR` gate. Naming both roles in the matcher is explicit and local.
- **Expose `detail` as a typed/structured object per event type.** Rejected for now: the event vocabulary is open (a plain string column, no enum) and enterprise extensions may add types; a raw JSON string keeps the contract stable and the rendering concern on the client.
