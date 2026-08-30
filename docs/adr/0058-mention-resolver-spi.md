# ADR-0058: MentionResolver — a third published contract in qnop-spi

- **Status:** Accepted
- **Date:** 2026-08-30
- **Deciders:** devtank42 (with Claude)

## Context

Mentions (#462) resolve a `@slug` token to exactly one user via the profile slug. The first enterprise feature wave wants team mentions (qnop-ee#1): a token that stands for a *set* of users. The community core owns the mention machinery — parsing, access-scoping, anonymity policy (ADR-0038), persistence, notification — and must stay ignorant of teams-as-mentionables (issue #598). Cross-namespace slug uniqueness (#595) guarantees a token belongs to at most one namespace, so resolution never has to arbitrate.

The question was where the seam lives: an internal Spring interface in `qnop-core`, or a published contract in `qnop-spi`. An enterprise module builds against the *published* SPI artifact only (ADR-0002/0003); an internal interface would force qnop-ee to depend on `qnop-core` internals, exactly what the polyrepo boundary forbids. ADR-0049 already frames server extension points as SPI contracts.

## Decision

`qnop-spi` grows a third published contract, `io.qnop.spi.mention`:

- **`MentionResolver`** — `Set<UUID> resolve(MentionContext, String slug)`: the user ids a token addresses, empty when the resolver does not own the slug. All registered resolvers see every token; answers are unioned. Pure JDK, Spring-free, ArchUnit-guarded like the other two contracts.
- **`MentionContext`** — record of `documentId`, `ownerId`, `authorId`.

Wiring follows the list-of-contributors pattern (not `@ConditionalOnMissingBean` replacement): the Community default `UserSlugMentionResolver` (user profile slugs, case-insensitive) is always registered, and an add-on contributes resolvers for *other* namespaces beside it — slug uniqueness keeps their answers disjoint, so ordering never matters.

Two invariants stay in the core, deliberately outside the seam:

1. **The access rule is the core's.** Every id a resolver returns passes `CommentMentionService`'s document-access check before anything is persisted or notified. A resolver names whom a token stands for; it can never widen who may be mentioned. It may apply a narrower rule of its own before answering.
2. **The persisted shape stays per-user `comment_mention` rows.** However a token expanded, the notification path (mail, opt-out, dedup) sees plain user mentions and needs no change.

Anonymity is also enforced before the seam: in anonymous reviews resolvers are never called (ADR-0038).

The UI counterpart — roster picker, pill renderer and excerpt names consulting registered mention contributors — lands with the same issue as the in-process seed of the ADR-0039 runtime extension model (`qnop-ui/src/extensions/mentions.ts`, the germ of `qnop-ui-spi`).

## Consequences

- `qnop-spi` now publishes three contracts (`StorageProvider`, `DocumentExtractor`, `MentionResolver`); semver discipline (ADR-0015/0046) applies unchanged.
- qnop-ee#1 implements team mentions as one `MentionResolver` bean plus a UI contributor — no community change.
- A community extension can add its own mention namespace under the same rules (ADR-0049 goal).
- The test-only fake contributor pattern (unit tests here) is the seed of the extension test kit ADR-0049 defers.

## Alternatives considered

- **Internal Spring seam in `qnop-core`** — rejected: qnop-ee must not link core internals (ADR-0002/0003).
- **`@ConditionalOnMissingBean` replacement of the whole resolution step** — rejected: an add-on would have to re-implement user resolution and could accidentally widen access; contribution beside the default is strictly additive.
- **Resolver returns principals with their own access semantics** — rejected: the access rule is a security invariant and stays un-delegable.
