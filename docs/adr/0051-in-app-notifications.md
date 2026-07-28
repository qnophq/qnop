# ADR-0051: In-app notifications — fan-out on write, identity resolved on read

- **Status:** Accepted
- **Date:** 2026-07-27
- **Deciders:** devtank42 (with Claude)

## Context

Review activity reaches users by e-mail only (issue #316). There is no in-app record: a reviewer cannot see inside qnop that three things happened on their reviews, and the top-right bell has been a `ComingSoonPopover` since the shell was built. Issue #538 adds persisted per-user notifications with three surfaces — bell quickview, detail page, and a "Messages" inbox.

Three properties of the existing code shape every decision below.

1. **The recipient rules are the expensive part, and they already exist.** `ReviewNotificationService` is ~450 lines of hard-won, issue-by-issue policy: mentioned users get the higher-ranked mention mail and are then *skipped* by the reply mail so nobody is mailed twice (#462); a dismissed annotation must reach its author, who is otherwise not a recipient, because their right to reopen is worthless if they never learn of the dismissal (#408); replies follow Slack-thread semantics (author plus everyone who joined); uploads and manual transitions go to the whole review circle, derived transitions to nobody. None of this is derivable from the event alone.

2. **Names are not a property of a user, they are a property of a (review, viewer) pair.** Under per-review anonymity (ADR-0038) the same actor is "Ada Admin" to the owner and "Reviewer 2" to a peer, and a review's privacy setting can change after the fact.

3. **A notification outlives the thing it describes.** Comments get edited, annotations get resolved, versions get superseded, and reviews get purged (ADR-0050).

## Decision

**Fan-out on write — one row per recipient — resolved once through the existing policy and delivered to two sinks; identity resolved at read time; content snapshotted at write time.**

### One resolution, two sinks

`ReviewNotificationService` no longer sends mail. It *resolves* a committed `ReviewEvent` into a list of `ReviewNotificationIntent`s and hands them to every registered `ReviewNotificationSink`. `MailNotificationSink` carries the previous behaviour unchanged; `InAppNotificationSink` writes one `notification` row per recipient. The alternative the issue proposed — a second `@TransactionalEventListener` deriving recipients independently — was rejected: it would duplicate all of point 1 above and drift from it on the first policy change.

The mention-versus-reply precedence survives as **ranked candidates**. Resolution may emit several intents for one recipient, ordered by rank (a mention outranks the reply it is contained in); each sink independently delivers *the first candidate it accepts*. This reproduces the existing mail behaviour exactly — a user who muted mention mails still falls through to the reply mail — while the in-app sink, which accepts everything, records the mention. Encoding the precedence as data rather than as control flow is what lets two sinks with different opt-outs share one resolution.

### E-mail settings gate e-mail only

The global `notifications.review_emails_enabled` switch and the per-user `EMAIL_REVIEW_NOTIFICATIONS` / `EMAIL_MENTIONS` opt-outs moved *into* the mail sink. In-app delivery is gated only by what is structurally required: the recipient is an enabled user and is not the actor.

This deliberately departs from issue #538's acceptance criterion "honoring existing opt-outs". Applying an e-mail opt-out to the inbox would defeat the feature's stated purpose — an inbox exists precisely so qnop is usable *without* living in your mailbox, so the users who muted mail are the ones who need it most. A missing e-mail address must likewise not suppress in-app delivery. Dedicated in-app preferences are out of scope; when they arrive they belong in the in-app sink's `accepts`, exactly where the mail opt-outs now live.

The cost is that resolution now runs even when review e-mails are globally off, where it previously short-circuited before any lookup.

### Identity on read, content on write

A notification row stores **ids** for everything that carries identity — `actor_id`, `recipient_id`, `document_id`, `annotation_id`, `comment_id` — and never a display name. The actor's name is resolved per recipient through `ReviewIdentityResolver` when the notification is read, so an anonymous review stays anonymous even if its privacy setting changed after the row was written. A snapshot of the rendered line would have baked in a name the recipient may no longer be allowed to see.

The inverse holds for everything that carries no identity: the annotation excerpt, the decision, the version number and the workflow transition are **snapshotted at write time** into typed nullable columns. They describe what happened *then*; re-deriving them on read would make a notification about an edited comment quote text the comment no longer contains, and one about a since-deleted comment unrenderable.

Read-time resolution costs roughly six queries per distinct document, so the read service resolves each document once per request and reuses it across that page's rows.

### Visibility is re-checked on read, and rows die with their review

Being the recipient is necessary but not sufficient: rendering re-checks `DocumentAccessService.isVisible`, so a notification about a review the user has since been removed from renders as a tombstone rather than leaking its title. `document_id` is `ON DELETE CASCADE` — without it the purge (ADR-0050), which deletes a document and relies entirely on cascades, would fail on a foreign key. `recipient_id` cascades from `qnop_user` for the same reason.

### Poll now, push later

The badge and quickview poll (`refetchInterval` plus refetch-on-focus), consistent with ADR-0013 deferring Redis. The SSE transport of qnophq/qnop-ee#5 can later push the same counter; nothing in the model assumes polling.

## Consequences

**Positive.** The recipient policy exists once and every future channel inherits it. Anonymity cannot be leaked by a stale notification. A purged review takes its notifications with it. The mail path's behaviour is unchanged, including the mention fall-through.

**Negative.** Resolution runs even with review mails disabled. Fan-out on write means N rows per event — the read path is trivial and indexed, but the table grows with activity and has **no retention policy yet** (follow-up: a `notificationSweep` scheduler job in the shape of #576/#577). Sinks are best-effort and independent: a failing in-app write must not cost the mail, so a partial delivery is possible and is logged rather than retried.

**Neutral.** In-app text is rendered by a dedicated renderer rather than the admin-editable mail templates, so blanking a mail template cannot empty the inbox; the two therefore need to be kept in phrasing sync by hand.

## Related

- **ADR-0038** — per-review privacy; the identity resolution this ADR defers to read time
- **ADR-0011** — the review workflow whose events are the notification source
- **ADR-0050** — the purge whose cascade requirement dictates the FK
- **ADR-0013** — Redis/real-time deferred, hence the poll-backed badge
- **ADR-0025** — settings machinery behind the e-mail opt-outs that now live in the mail sink
- **ADR-0021** — OpenAPI-first, followed by the `/notifications` contract
- Issues **#538** (this feature), **#316** (the e-mail path it parallels), **#537** / qnophq/qnop-ee#5 (live push, later)
