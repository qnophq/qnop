# ADR-0049: Extension packaging model — boot-time SPI plugins, not a runtime plugin framework

- **Status:** Proposed
- **Date:** 2026-07-25
- **Deciders:** devtank42 (with Claude)

## Context

qnop is open-core: an AGPL Community edition plus commercial add-ons in a private `qnop-ee` repo that builds against the published, versioned, Spring-free `qnop-spi` artifact and activates via Spring `@AutoConfiguration` + `@ConditionalOnMissingBean` — "classpath = edition" (ADR-0002/0003/0039). The first enterprise feature wave (qnop-ee#1–#5) is being cut along dedicated community seams (#598–#602): server extension points, the runtime UI extension model, and the enterprise Liquibase changelog seam that already exists in the master changelog.

Two ambitions raise the packaging question beyond "how does EE plug in":

1. **Open the software for community extensions** — third parties should be able to build and ship their own additions.
2. **Enterprise as plugins** — the EE bundle should be "just" a set of extensions using the same mechanics, keeping the core honest about its seams.

A runtime plugin framework (PF4J, with `pf4j-spring`) was considered as the vehicle: `@Extension` discovery, per-plugin classloaders, and install/start/stop/unload at runtime.

The trap to avoid: buying a framework for the two features qnop does not need (hot-loading, classloader isolation) while the actually hard work — stable contracts, compatibility discipline, a frontend extension story, a trust model — is identical under every vehicle.

## Decision

**1. qnop's extension model IS a plugin system — the Keycloak-shaped one, not the Jenkins-shaped one.** Extensions are discovered at boot from the classpath and are trusted code chosen by the operator. There is no runtime install/uninstall and no per-extension classloader. Installing an extension = placing its JAR next to the server and restarting; removing it = the reverse. This matches the operational reality of a self-hosted system whose extensions may carry schema migrations — a schema-changing "hot install" would be a restart event in disguise.

**2. Anatomy of a qnop extension** (the binding convention):

- A JAR built against the **published `qnop-spi`** (and, for DTO reuse, `qnop-api`) — never against `qnop-core` internals.
- Activation via **Spring `@AutoConfiguration`**; behaviour overrides via `@ConditionalOnMissingBean`; additive contributions via the list-of-contributors seams (#598–#602 pattern).
- **Schema:** Liquibase changesets in the extension's own namespace — `db/changelog/extensions/` with ids `x####-<extensionId>-*` — generalizing the existing enterprise seam. The enterprise suite keeps its `e####-*` namespace. Extension changesets run after community migrations, in filename order, and must be additive.
- **REST:** extension endpoints live under `/api/ext/<extensionId>/…` only. The published OpenAPI contract (ADR-0021, `qnop-api`) describes the community surface exclusively; an extension ships its own contract and client if it needs one.
- **UI:** contributions register through the runtime UI extension model (ADR-0039). No second frontend mechanism.
- ArchUnit-style conventions for extensions are the extension author's concern; the core's rules keep guarding the core.

**3. Trust boundary — in-process means operator-trusted.** An in-process extension has full database and process access by construction; no JVM mechanism changes that (the SecurityManager is gone). Therefore: in-process extensions are installed deliberately by the operator, exactly like a Keycloak provider. If an *untrusted* extension tier (marketplace-style) is ever wanted, it runs **out-of-process** against a webhook/REST contract — the same isolation posture the project already applies to copyleft tooling (LibreOffice, out-of-process only, ADR-0007/0010). That tier is explicitly deferred and would be its own ADR.

**4. Compatibility policy.** `qnop-spi` (and `qnop-api`) follow semver (ADR-0015 discipline, published via ADR-0046). An extension declares the spi major it builds against; the compatibility promise is "extension built against spi `X.y` runs on servers shipping spi `X.*`". A published **extension test kit** — grown from the "test-only fake contributor" pattern the seam issues establish — lets authors verify their extension against the contract without a full server checkout. Test-kit shape and the exact declaration mechanism are deferred details.

**5. Sequencing — direction now, binding formalization later.** This ADR fixes the direction so the seam work (#598–#602) is built plugin-shaped from the start. The public formalization — author documentation, extension template repository, the test kit, a compatibility matrix — happens once the first wave of seams has shipped with its first real consumer (qnop-ee) and external authors actually exist. Extension points that never had a second consumer are almost always cut wrong; the EE features are the proving ground.

## Alternatives considered

- **PF4J (+ `pf4j-spring`).** Rejected. Its distinctive features are the ones qnop does not need: runtime lifecycle (the operating model is boot-time, and schema-carrying extensions make hot-unload fictional) and per-plugin classloaders (which fight Hibernate entity scanning, transaction proxies, Jackson, Liquibase and OpenAPI generation — all of which assume one classpath — while providing no real security boundary for in-process code). `pf4j-spring` is thinly maintained; pairing it with Spring Boot 4 would make qnop a pioneer on an unpaid frontier. The genuinely hard problems — contract stability, compatibility, the frontend story, trust — are vehicle-independent and remain exactly as large under PF4J.
- **OSGi.** Rejected without ceremony: everything above, heavier.
- **Out-of-process plugin host (Mattermost/Grafana model) as the primary mechanism.** Not rejected — deferred. It is the right answer for *untrusted* extensions (see Decision 3), but as the only mechanism it would tax the trusted 95% case (EE, operator-built extensions) with RPC latency, serialization contracts and a process supervisor, for isolation those extensions do not require.
- **No community extension story (EE-only seams).** Rejected: opening the platform is a stated goal, and the marginal cost over the EE seams is small — the mechanics are shared.

## Consequences

- The seam issues (#598–#602) double as the first public extension points; building them "plugin-shaped" costs nothing extra now and prevents a later re-cut.
- The enterprise edition becomes structurally "a suite of extensions" — the core cannot quietly grow EE-only special cases, because EE passes through the same seams a community extension would.
- Operators get a simple mental model: edition and extensions are the classpath; every change of either is a deliberate restart.
- Community extension authors get real but bounded promises: versioned contracts, a test kit (later), and no pretense of sandboxing — an installed extension is trusted code.
- A future marketplace/untrusted tier requires new architecture (out-of-process host) and a new ADR; nothing in this model forecloses it.
- Deliberately deferred: the extension test kit, the template repository, the spi-major declaration mechanism, and any runtime lifecycle beyond boot-time discovery.
