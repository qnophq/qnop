# ADR-0048: Cross-namespace slug uniqueness (users and teams)

- **Status:** Accepted
- **Date:** 2026-07-24
- **Deciders:** devtank42 (with Claude)

## Context

Users carry immutable, human-readable profile slugs (issue #486), teams carry immutable URL slugs (issue #470). Each namespace is unique on its own: `UserSlugService` and `TeamSlugService` are mirrored twins that derive a kebab-case base from the display name, probe `base-n` candidates against **their own table** (`existsBySlugIgnoreCase`), and rely on the per-table case-insensitive unique indexes (`ux_qnop_user_slug_lower`, `ux_team_slug_lower`) to backstop the check-then-save race.

@-mentions (issue #462) turned user slugs into **reference tokens inside stored Markdown**: `@anna-krause` in a comment body is resolved — on the server for notifications, on the client for rendering — purely by its slug. Team mentions (issue #596) want the same token shape for teams. With two independent namespaces, `@design` could name both a user and a team, forcing a documented precedence rule into every resolver (server notification path, client pill renderer, excerpt resolver, search) — and a subtle identity trap for users ("I mentioned the designer, qnop pinged the whole team").

The trap to avoid: ambiguity handled by convention in four places drifts; ambiguity removed at the source cannot.

## Decision

**1. One flat slug namespace.** A slug identifies exactly one principal — user *or* team. Cross-namespace collisions are prevented at allocation time; mention resolution becomes a simple "look up user, else team" with no precedence semantics.

**2. Allocation checks both tables.** Both slug services probe candidates through a shared `SlugNamespace` helper that consults `qnop_user` *and* `team` case-insensitively. One helper, two callers — the mirrored services cannot drift apart.

**3. The cross-table race closes with a Postgres advisory lock.** Per-table unique indexes cannot guard a collision *across* tables (simultaneous creation of user "Design" and team "Design"). Allocation therefore takes a transaction-scoped `pg_advisory_xact_lock(hashtext(lower(slug)))` before probing; the lock releases with the transaction. Postgres-only is set by ADR-0020.

   *Considered and rejected:* a `slug_registry` table with a real cross-kind unique constraint. It is the only guard that also catches direct DB edits, but it adds a second write on every user/team creation and a cleanup obligation on every deletion path — a standing drift risk for a race the advisory lock already closes for all application paths. Documented here so a future principal kind (e.g. service accounts) re-evaluates deliberately.

**4. Existing collisions: teams yield.** A one-time additive Liquibase changeset (post-1.0 rule) re-allocates any team slug that collides case-insensitively with a user slug to its next free `base-n` candidate. User slugs are **external identity** — they live in profile links, mails, and as mention tokens inside stored Markdown bodies that nobody rewrites; team slugs are internal `/my-teams/…` links. Expected collision count in real data: approximately zero. A re-slugged team's saved links 404 (slugs are non-enumerable by design); accepted.

**5. Future principal kinds join the namespace.** Anything that becomes mention-addressable by slug allocates through the shared helper. The namespace is flat by definition, not by accident.

## Consequences

- Mention resolution (issue #596) needs no precedence rule anywhere: server (`CommentMentionService`), pill renderer (`MentionLink`), excerpt resolver (`useMentionNames`) and search all do "user, else team" and get the same answer by construction.
- Slug allocation acquires a Postgres-specific primitive (advisory lock). This is the second such dependency after ADR-0033's job queue (`FOR UPDATE SKIP LOCKED`); the project is deliberately Postgres-native (ADR-0020).
- Direct DB edits can still create collisions past the application guard. The per-table indexes keep each namespace internally consistent; cross-namespace consistency relies on writes going through the services — same trust level as every other service-enforced invariant.
- A user slug can now be "taken" by a team name and vice versa; the `base-n` fallback makes this invisible to the person creating either.
- Re-slugged teams (migration, expected ~0) break saved `/my-teams/…` links with a 404.
