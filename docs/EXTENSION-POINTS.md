<!--
Copyright (c) 2026-present devtank42 GmbH
SPDX-License-Identifier: AGPL-3.0-only
-->

# Extension points — the reference

Every seam through which code that is *not* this repository extends qnop: what
exists today, how each seam is wired, which invariants the core refuses to
delegate, and what is planned. Written as a lookup work for human developers
and AI agents alike — if you are about to build against, add, or modify an
extension point, start here, then follow the ADR links for the reasoning.

**Keep this document in sync:** a PR that adds or changes an extension point
updates this file in the same PR (working rule 5 applies — plus an ADR when a
published contract grows or changes).

## The model in one paragraph

qnop's extension model is a plugin system of the Keycloak shape, not the
Jenkins shape ([ADR-0049](adr/0049-extension-plugin-packaging-model.md)):
extensions are **trusted code, chosen by the operator, discovered at boot from
the classpath**. Installing one = placing its JAR next to the server and
restarting; there is no runtime install, no per-plugin classloader, no
sandbox — an in-process extension has full database and process access by
construction. "Classpath = edition"
([ADR-0012](adr/0012-edition-vs-entitlements.md)): the enterprise edition is
just a set of such extensions from the private `qnop-ee` repository, which
builds against the **published, versioned, Spring-free `qnop-spi` artifact
only** ([ADR-0002](adr/0002-open-core-via-polyrepo-and-published-spi.md),
[ADR-0003](adr/0003-agpl-boundary-is-the-spi.md) — the SPI is the AGPL line,
[ADR-0046](adr/0046-publish-spi-and-api-to-github-packages.md) — publishing).
Frontend extensions load at runtime as ESM bundles served by their JAR
([ADR-0039](adr/0039-enterprise-packaging-and-runtime-extensions.md)); the
in-process registries described below are the seams those bundles will feed.

## Conventions that hold across all seams

| Convention | Rule |
|---|---|
| **Wiring: contribution beside a default** | When answers are disjoint (mention namespaces, document formats), the seam is a `List<Contract>` and the Community default is always registered; extensions add to the list. Ordering must never matter. |
| **Wiring: replaceable default** | When exactly one implementation must exist (object storage), the Community default is `@ConditionalOnMissingBean`; an extension bean replaces it wholesale. |
| **Invariants stay core-side** | Access rules, anonymity (ADR-0038), persistence shapes and notification semantics are enforced by the core *around* the seam. An extension names things; it never widens permissions. |
| **Purity** | `qnop-spi` types are pure JDK — no Spring, no JPA, no internal modules. ArchUnit-enforced (`pluginContractStaysPure`). |
| **Compatibility** | `qnop-spi` follows semver (ADR-0015 discipline); "built against `X.y` runs on `X.*`". Growing a contract is minor; changing one is major and ADR territory. |
| **REST namespace** | Extension endpoints live under `/api/ext/<extensionId>/…` only. The published OpenAPI contract (`qnop-api`, ADR-0021) describes the community surface exclusively. |
| **Schema namespace** | Enterprise changesets are `e####-<feature>-*.yaml`, author `qnop-enterprise` — ids can never collide with community `####-*`. |
| **Testing pattern** | Every seam ships a *test-only fake contributor* in the community test suite proving the seam end to end — and proving behaviour is byte-identical without one. This pattern is the seed of the extension test kit ADR-0049 defers. |
| **Frontend** | UI contributions register through the runtime UI extension model (ADR-0039). No second frontend mechanism. |

---

## Existing extension points

### 1. `StorageProvider` — object storage (backend, published)

- **Contract:** `io.qnop.spi.storage.StorageProvider` — `put(key, content, length, contentType)`, `get(key) → Optional<StorageContent>`, `exists(key)`, `delete(key)`; throws `StorageException`. Listing via `StorageListing`.
- **Wiring:** replaceable default. The S3/MinIO adapter (`io.qnop.service.storage`, `S3Configuration`) is `@ConditionalOnMissingBean(StorageProvider.class)` — an extension providing its own bean replaces it entirely.
- **Core keeps:** the upload-then-commit staging registry and the orphan reaper ([ADR-0036](adr/0036-object-storage-lifecycle-staging-and-reaper.md)) sit *above* the provider — a provider stores bytes by opaque key and knows nothing about commit semantics.
- **Owned by:** [ADR-0005](adr/0005-binary-documents-in-object-storage.md), ADR-0036; issue #243.

### 2. `DocumentExtractor` — document formats (backend, published)

- **Contract:** `io.qnop.spi.extract.DocumentExtractor` — `supports(contentType)` against the **server-sniffed** MIME type (never the filename), `extract(InputStream) → RenderedDocument` (surfaces + positioned `TextSpan`s, the canonical extraction of [ADR-0032](adr/0032-document-representation-and-rendering-pipeline.md)).
- **Wiring:** contribution beside a default — the extraction job handler iterates the registered list and uses the first extractor whose `supports` claims the sniffed type; none matching fails the version permanently. Community ships the PDF extractor; DOCX arrives as PDF via the out-of-process LibreOffice conversion ([ADR-0010](adr/0010-docx-representation-strategy.md)) *before* this seam.
- **Known gap, tracked:** the upload accept gate is still hard-coded to PDF and DOCX, so an extractor for a new format is rejected before it runs — #601 derives the gate (and the `GET /api/v1/config` `supportedFormats` advertisement) from the registered extractors.
- **Owned by:** ADR-0010/0032; the seam promise "a new format is an added implementation, not a core rewrite".

### 3. `MentionResolver` — mention namespaces (backend, published)

- **Contract:** `io.qnop.spi.mention.MentionResolver` — `resolve(MentionContext, slug) → Set<UUID>` (slugs arrive lower-cased by the parser); `MentionContext(documentId, ownerId, authorId)`. **The returned UUIDs are always user ids** (rows of `users`): a resolver for another namespace (a team) *expands* its principal into member user ids — the team's own id never leaves the resolver. Empty set = "not my namespace"; cross-namespace slug uniqueness (#595) guarantees at most one owner per token.
- **Wiring:** contribution beside a default — `UserSlugMentionResolver` (profile slugs, case-insensitive) is always registered; all resolvers see every token and their answers are unioned.
- **Core keeps:** the document-access rule runs over every resolved id (a resolver can never widen who may be mentioned, only apply a narrower rule of its own); persisted shape stays per-user `comment_mention` rows, so mail/opt-out/dedup are extension-agnostic; anonymity (ADR-0038) is enforced *before* resolvers are called.
- **Owned by:** [ADR-0058](adr/0058-mention-resolver-spi.md); issue #598. First consumer: qnop-ee#1 (team mentions).

### 4. `PublishedEventListener` — the review-event stream (backend, published)

- **Contract:** `io.qnop.spi.event.PublishedEventListener` — `on(PublishedEvent)`; `PublishedEvent` carries a stable catalogued `type` (`PublishedEventTypes`, eight names today), `occurredAt`, `documentId`, `actorId` and an identifier-only attribute map. **No customer content** — bodies and titles never enter the stream; whether a consumer may read the subject is the API's permission question.
- **Wiring:** contribution beside a default of *nobody* — all registered listeners hear every event. Called after commit, off the request thread, each listener isolated (a throwing listener is logged and costs only itself; a full dispatch queue drops with a warning rather than slowing the request). Delivery is best-effort in-process: durability, retries, signing, SSRF policy live in the consumer.
- **Core keeps:** the catalogue (the internal→published mapping is exhaustive over the sealed `ReviewEvent` hierarchy, so a new internal event is a compile error until someone decides its published fate) and the isolation guarantees. `ReviewNotificationSink` stays internal and recipient-shaped beside this event-shaped seam.
- **Owned by:** [ADR-0059](adr/0059-published-event-stream.md); issue #685. First consumer: qnop-ee#18 (webhooks).

### 5. Enterprise Liquibase changelog seam (schema)

- **Contract:** the master changelog ends with `includeAll: classpath*:db/changelog/enterprise/` with `errorIfMissingOrEmpty: false` — a no-op without enterprise JARs. An extension ships its changesets under that path on its own classpath entry.
- **Rules:** file names `e####-<feature>-*.yaml`, author `qnop-enterprise`; applied after all community migrations, in filename order. The community fold-rule ("modify the existing changeset") does **not** apply across the boundary — an extension owns its own history.
- **Owned by:** ADR-0039 §2; issue #254.

### 6. UI mention contributor registry (frontend, in-process)

- **Contract:** `qnop-ui/src/extensions/mentions.ts` — `MentionContributor` (`candidatesFor(documentId)`, `resolve(slug)`) returning `MentionPrincipal` (`id`, `name`, `slug`, `kind`, optional `avatarUrl`/`href`/`hint`). Register with `registerMentionContributor(c)` (returns the deregistration); announce async data with `notifyMentionContributionsChanged()`; hosts subscribe via `useMentionContributors()` (`useSyncExternalStore`).
- **Semantics:** here `id` is the **principal's own id** (e.g. the team id) and is presentation-only — `kind` discriminates the namespace, and a contributed id must never be fed to user endpoints (avatar, profile). Contrast with the backend seam (§3), where only user ids cross.
- **Anonymity:** rendering deliberately resolves *public* identities — exactly as user pills already do (a typed `@user-slug` in an anonymous review renders the public profile pill today). ADR-0038 is enforced where it matters: no roster is offered and nothing is resolved, persisted or notified server-side. One knowing asymmetry: in anonymous reviews plain-text excerpts leave user slugs raw (the roster is empty) while contributed slugs still resolve — both are workspace-public information, so neither leaks a hidden identity.
- **Consumers:** `useMentionRoster` (picker candidates, never in anonymous reviews), the composer picker (hint + avatar), `MentionLink` (pill; contributor consulted before the user-profile fetch), `useMentionNames` (plain-text excerpts).
- **Status:** the in-process seed of the ADR-0039 runtime UI extension model — today filled by tests only; the extension loader (import map + ESM bundles + `qnop-ui-spi`) will feed it. New UI seams (#599/#600/#602) reuse this registry pattern.
- **Owned by:** issue #598; ADR-0039 is the packaging frame.

### 7. Edition & capability surface — `GET /api/v1/config`

Not an extension point itself, but the channel through which extensions become visible to clients: edition and entitlements (ADR-0012), `supportedFormats` (to be derived from registered extractors, #601), and — per ADR-0039 — the list of frontend extension entry URLs with the `qnop-ui-spi` contract version they were built against.

---

## Planned extension points (tracked, not yet built)

| Seam | What it will extend | Issue | First consumer |
|---|---|---|---|
| **Composer-mode contribution** | The Markdown composer's mode strip: a registered mode adds a tab and its own editing surface over the controlled contract (`value` = raw Markdown stays the storage format by construction, `onChange`, submit/disabled/fullscreen, roster/attachment/emoji affordances). | [#599](https://github.com/qnophq/qnop/issues/599) | qnop-ee#2 (WYSIWYG) |
| **Message-row actions + badge slots** | Per-message icon actions and a timestamp-adjacent badge on comment rows / annotation heads, with message context (ids, own-message flag, status, finalization) so contributors decide visibility without the row hard-coding policy. | [#600](https://github.com/qnophq/qnop/issues/600) | qnop-ee#3 (edit own messages) |
| **Edit-safe mention re-resolution** | `CommentMentionService` becomes replayable for an edited body: replace the comment's mention rows, report only the newly-mentioned ids (the notify-delta). Creation callers unchanged. | [#600](https://github.com/qnophq/qnop/issues/600) | qnop-ee#3 |
| **Accepted-format gate from extractors** | Ingest validation + advertised `supportedFormats` derive from registered `DocumentExtractor`s; possibly a "which media types" capability on the SPI contract (ADR amendment if so). | [#601](https://github.com/qnophq/qnop/issues/601) | qnop-ee#4 (image review) |
| **Review-event projection** | ~~Now exists~~ — the published stream above (§4, ADR-0059) covers the event needs; #602 keeps only the facades and UI slot below. | [#602](https://github.com/qnophq/qnop/issues/602) | qnop-ee#5 (live feed) |
| **Audience / identity / access facade** | Read-only facade over the notification path's recipient resolution, the per-review anonymity identity (ADR-0038) and the `listAnnotations` authorization rule — so extensions reuse, never re-implement, the security-relevant scoping. | [#602](https://github.com/qnophq/qnop/issues/602) | qnop-ee#5 |
| **Review live-channel UI slot + invalidation facade** | A lifecycle hook while a review surface is mounted (where an SSE client lives) and a small facade over the query keys ("annotations of X changed") so pushes become normal authorized refetches. | [#602](https://github.com/qnophq/qnop/issues/602) | qnop-ee#5 |
| **`qnop-ui-spi` + extension loader** | The published npm contract (slot types + registry) and the import-map/ESM runtime loader with the `/config` compatibility handshake. Built with the first enterprise UI feature. | ADR-0039 | — |
| **Extension test kit** | Published harness grown from the fake-contributor pattern, so authors verify against the contract without a server checkout. | ADR-0049 (deferred) | — |
| **Out-of-process untrusted tier** | Marketplace-style extensions against a webhook/REST contract — explicitly deferred, own ADR when wanted. | ADR-0049 §3 | — |

---

## Adding a new extension point — the checklist

1. **Issue first** (working rule 1); name the first consumer.
2. **Decide the boundary deliberately:** does the contract belong in published `qnop-spi` (an enterprise/community extension must implement it → yes, since extensions link only the published artifact) or is it an internal Spring seam? Growing `qnop-spi` is ADR territory.
3. **Pick the wiring** from the conventions table: contribution-beside-default for disjoint namespaces, `@ConditionalOnMissingBean` for a replaceable singleton.
4. **Keep the invariants core-side:** access, anonymity, persistence shape, notification semantics. Write them into the contract's javadoc.
5. **Prove it with a test-only fake contributor** — and prove byte-identical behaviour without one.
6. **Frontend seams** register through the extensions registry pattern (`qnop-ui/src/extensions/`), never a parallel mechanism.
7. **Update this document** in the same PR; update `docs/ARCHITECTURE.md`/`CLAUDE.md` if the published-contract count changes.
