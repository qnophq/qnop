# ADR-0064: Team-membership consent — leads invite, only admins grant LEAD

- **Status:** Accepted
- **Date:** 2026-11-12
- **Deciders:** devtank42 (with Claude)

## Context

Team leads manage their own team's membership without being admins (issue #470). After #917 removed
the data exposure on that surface, two workflow gaps remained (issue #936, part of the 2026-11
security review, epic #896):

- A lead could add **any** enabled user to the team immediately, without that user's consent. Team
  membership is an access grant: it makes the team's reviews visible (`ReviewParticipant` rows for
  the team), puts the user in the principal directory's team roster, in mention rosters and in
  notification fan-out.
- A lead could grant other members the `LEAD` role, i.e. hand out management rights over the team
  without any administrator involvement.

Team membership feeds a large number of access decisions — document visibility, dashboards, search,
mentions, notifications, the external-participant and review facades — each of which queries
`team_membership` directly.

## Decision

1. **A lead's add is an invitation.** When the actor is a team lead who is not a global admin,
   `POST /teams/{teamId}/members` records a pending invitation and answers `201` with
   `status: INVITED`. The invitation grants nothing. The invited user sees it under
   `GET /teams/invitations` (the "My Teams" page) and either accepts
   (`POST /teams/invitations/{teamId}/accept` → a `MEMBER` membership) or declines
   (`POST /teams/invitations/{teamId}/decline` → the invitation is deleted). A lead or admin can
   revoke a pending invitation (`DELETE /teams/{teamId}/invitations/{userId}`, and
   `DELETE /admin/teams/{teamId}/invitations/{userId}` on the admin console). Only the invited user
   can answer: the lookup is keyed on the caller, so anybody else gets 404.
   Accept, decline and revoke all take the team's row lock and delete the invitation with a counted
   delete: when two of them race, one wins and the other is a plain 404 — never a stale-state error.
2. **Admin adds stay immediate.** Both admin surfaces (`/admin/teams/**` and an admin acting through
   `/teams/**`) create an `ACTIVE` membership directly, with any role. A direct add supersedes a
   pending invitation of the same user.
3. **Only admins grant LEAD.** A lead who is not an admin can neither invite as `LEAD` nor promote a
   member to `LEAD`; both answer `403 LEAD_GRANT_REQUIRES_ADMIN`. Leads keep every other existing
   right: demoting a lead, removing members, the last-lead and self-change guards (#542). We chose
   this over a "lead requests, admin approves" flow because there is no admin approval queue in the
   product today, a request flow would need its own state, UI and notifications for a rare event,
   and an admin can already promote in one click from the admin team page.
4. **Invitations live in their own table** (`team_invitation`, migration `0041`), not as a status
   column on `team_membership`. Every existing query that derives access from `team_membership`
   therefore keeps meaning "active member" by construction; a status column would have required a
   filter on each of those queries, and a single missed filter would silently grant access to an
   invited user. Existing memberships are untouched and remain active. The API still exposes a
   `TeamMembershipStatus` (`ACTIVE` | `INVITED`) on team-member DTOs, and team details carry a
   separate `pendingInvitations` list — visible only to callers who may manage the team.
5. **Quota.** The per-team member quota (ADR-0057) is checked when the invitation is sent (an early
   refusal) and again, under the team row lock, when it is accepted — the moment the seat is taken
   (not when the user is already a member, which takes no seat). Pending invitations do not count
   as members.
6. **Pending cap.** A team holds at most 50 unanswered invitations
   (`TeamService.MAX_PENDING_INVITATIONS`); one more answers `409 TOO_MANY_PENDING_INVITATIONS`.
   A fixed cap rather than counting invitations against the member quota, because that quota may be
   unlimited (0) and the point is to bound what a lead can leave outstanding. There is no
   re-invite cooldown after a decline: an invitation grants nothing, so a repeat invite is a
   nuisance at worst, and the user can decline again. This is an accepted residual.
7. **Disabled teams.** Disabling a team (admin console) deletes its pending invitations. A disabled
   team therefore has none to list, accept or decline; re-enabling it does not bring them back —
   the lead invites again.

## Consequences

- An invited user has no access to the team or its reviews until they accept; ITs prove this
  (`TeamInvitationConsentIT`).
- Leads need an admin to create co-leads. This is deliberate friction on a privilege grant.
- Invitations do not expire; they stay until accepted, declined, revoked, the team is disabled, or
  the team or the user is deleted (FK cascade).
- **Notification.** The invitation surfaces in the UI ("My Teams") only. The in-app inbox
  (ADR-0051) and its mail cadence are review-scoped — every notification type is a review event
  rendered against a document and fed into the review digest — and there is no team mail template
  today. A team-invitation notification is tracked in #948.
- **Audit.** Team-membership changes are not part of the audit vocabulary today (ADR-0042/0043), so
  invitation events are not audited either; team audit events are tracked in #948.
- API change for clients: `POST /teams/{teamId}/members` by a non-admin lead no longer creates a
  member; it returns `status: INVITED`.

## Alternatives considered

- **Status column on `team_membership`** (`ACTIVE`/`INVITED`): matches the DTO more literally, but
  every access query would need `status = 'ACTIVE'` and future queries would have to remember it —
  a fail-open design for an access grant. Rejected in favour of the separate table.
- **Lead-requested LEAD grants approved by an admin:** more flexible, but needs a request state, an
  admin queue and notifications; the existing admin team page already covers the need. Rejected.
- **A setting to let leads grant LEAD:** adds a configuration surface for a privilege escalation
  path we do not want to make easy. Rejected.
