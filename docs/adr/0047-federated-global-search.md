# ADR-0047: Federated Global Search behind a SearchService Port

- **Status:** Accepted
- **Date:** 2026-07-23
- **Deciders:** devtank42 (with Claude)

## Context

The top-bar search box has been a placeholder since #514 — there is no way to jump to a review, person, or team by typing its name (issue #540). Meanwhile, per-type, authorization-scoped search already exists in three places: the reviews overview (`DocumentRepository.findVisibleTo` — owner OR direct participant OR member of a participant team, title match only), and the principal directory (`UserRepository.searchEnabledPrincipals` — display name/username, deliberately never email; `TeamRepository.searchEnabledPrincipals` — enabled team names). ADR-0013 defers a search engine and names PostgreSQL FTS (`tsvector` + GIN) as the starting point once real search arrives, with OpenSearch only when ranking/faceting/corpus size demand it.

The trap to avoid: a global search that builds its own query path becomes a second, weaker authorization surface — one forgotten predicate and a stranger finds a confidential review title.

## Decision

**1. Federation over a new index.** Global search queries each entity type through its *existing* authorization-scoped repository query and merges the results. Scoping is correct by construction: a hit in the global search is by definition a hit the caller could already reach through the reviews overview or the principal directory. No new tables, no schema change, no second access path.

**2. A `SearchService` port in `qnop-core` (`io.qnop.service.search`), Community default `LikeSearchService`.** The port exposes a grouped `quick` view (top 5 per type + full match count, for the dropdown) and three paged per-type views (for the results page). The default adapter federates the Postgres `LOWER … LIKE` queries — building the pattern exactly as the overview/directory services do. **This is deliberately simpler than the Postgres FTS start ADR-0013 sketched**: `LIKE` federation reuses proven queries verbatim and needs no migration; FTS (`tsvector` + GIN + ranking) or OpenSearch replace the *adapter* when substring matching stops being enough — the callers and the contract stay put. This refines, not contradicts, ADR-0013.

**3. Scoping rules are the search's constitution:**
   - **Reviews:** `findVisibleTo`, matched by **title**. Discussion texts surface as their **own result types** — **annotations** (a thread's opening text, #301) and **comments** (the replies) — so a hit says what actually matched. Both discussion queries carry the exact `canSeeThread` predicate from ADR-0038: in a `PRIVATE` review a foreign thread neither matches nor is quoted — only the owner, the thread's author, and admins search (and excerpt) it. A discussion hit carries a windowed excerpt of the matching text, its review context, the annotation's status for the cue, and the `?annotation=`/`&comment=` deep-link facts. **Author names are never a search key**, and no author name travels on a hit — matching *content someone may read* is safe under anonymity (ADR-0038 hides who wrote it, not what it says); matching *by author* would be an identification oracle and stays out of scope. **Admins search their own reviews like anyone else**, mirroring the reviews overview (org-wide oversight is the audit trail, not search — the #563 moderation direction may revisit this deliberately); adminship widens only the thread-visibility predicate, as everywhere else.
   - **Users:** enabled only, matched by display name/username, **never by email** — email search and disabled accounts stay behind `/admin/users`.
   - **Teams:** enabled only, matched by name. Every hit carries `viewable` (member or admin), because the roster page is member-or-admin-only (#470) — the client lists non-viewable teams (their names are public via the principal directory anyway) but does not link them.

**4. Contract: one quick + five typed paged operations** (`GET /search`, `/search/reviews|annotations|comments|users|teams`) rather than a single polymorphic endpoint — one concrete schema per operation is the repo convention, and the generated TypeScript client handles it cleanly. Queries shorter than 2 characters (trimmed) answer **empty, never 400** — a box firing per keystroke must neither error nor dump the whole workspace.

**5. Frontend:** the top-bar pill becomes a controlled, debounced input with a grouped quickview dropdown (reviews with their milestone track — the #568 state language at row scale —, annotations and comments with their status cue and matched excerpt, people with avatars, teams with a lock on unreachable rosters) and a "see all N" continuation onto `/search?q=…`, where query, type and page live in the URL. ⌘K/Ctrl+K focuses the box from anywhere (advertised by a kbd hint in the resting pill), which grows to reading width on focus — the Docker Hub pattern, and the one deliberate layout animation (off under reduced motion).

## Consequences

- A later search upgrade (FTS ranking, typo tolerance, OpenSearch faceting) is an adapter swap behind `SearchService`; the contract and both UI surfaces are untouched.
- `LIKE '%q%'` cannot use plain b-tree indexes; at Community scale (the same queries already back the overview and the directory) this is acceptable, and the port is exactly the seam to fix it when it is not.
- The per-group `total` needed `Page`-returning variants of the principal searches; the `List` variants stay for the directory.
- Deliberately deferred: arrow-key roving in the dropdown, a mobile entry point, fuzzy matching, and search **by author name** (the ADR-0038 identification-oracle line — discussion *content* is searchable, authorship is not).
