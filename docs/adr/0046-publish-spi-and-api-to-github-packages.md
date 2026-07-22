# ADR-0046: Publish `qnop-spi` and `qnop-api` to GitHub Packages

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** devtank42 (with Claude)

## Context

The open-core split (ADR-0002) keeps the commercial add-ons in a separate, private `qnop-enterprise` repository that builds against the **published** `qnop-spi` artifact and plugs in via Spring auto-configuration (ADR-0003). The REST contract is likewise a published, versioned surface (`qnop-api`, ADR-0015/0021). But no `maven-publish` configuration existed anywhere in the build, so neither artifact could actually be consumed outside this repo — `qnop-enterprise` had nothing to depend on.

We need to publish the Spring-free published contracts to a Maven repository the enterprise repo can resolve, without standing up new infrastructure and without loosening the AGPL boundary.

## Decision

**1. Publish to GitHub Packages, via a `qnop.publish-conventions` convention plugin.** A new precompiled plugin in `build-logic/` (alongside `qnop.java-conventions`, ADR-0006) applies `maven-publish` + `signing` and configures a single `MavenPublication` from the `java` component, the GitHub Packages Maven repository (`https://maven.pkg.github.com/qnophq/qnop`), full POM metadata (name, description, `url`, the **AGPL-3.0-only** licence, organisation, developer, SCM) and a javadoc jar (the sources jar already comes from `qnop.java-conventions`). GitHub Packages is chosen over Maven Central because the consumer (`qnop-enterprise`) is a private GitHub repo already authenticated to the same org — no Sonatype account, namespace verification, or mandatory-signing hurdle, and access control comes for free. Ported from plugwerk's publish convention.

**2. Apply it only to the three Spring-free published modules:** `qnop-spi`, `qnop-api-model` and `qnop-api-endpoint`. `qnop-core`/`qnop-app` are the AGPL server and are **not** published (they are consumed as a running application/image, ADR-0040, not as a library); the `qnop-api` container project builds nothing and carries no publication.

**3. Credentials resolve from Gradle properties first, then the CI environment.** `gpr.user`/`gpr.key` (from `~/.gradle/gradle.properties`) let a maintainer publish locally; CI falls back to the Actions-provided `GITHUB_ACTOR` / `GITHUB_TOKEN`. No secret is ever hard-coded.

**4. Signing is opt-in and in-memory.** The `signing` block signs the publication **only** when an ASCII-armoured `SIGNING_KEY` is present in the environment (`isRequired = signingKey != null`); otherwise no signature task is wired. GitHub Packages does not mandate signatures, so a normal `build` and a SNAPSHOT publish stay frictionless, while a tagged release can attach signatures once a key is configured as a secret.

**5. Publish on both a tagged release and every `main` push.** `release.yml` gains a `./gradlew publish` step (it already holds `packages: write`), so a `vX.Y.Z` tag publishes the matching release artifacts next to the container image. A separate `publish-snapshot.yml` publishes the `-SNAPSHOT` artifacts on every push to `main` (guarded so it skips the brief non-SNAPSHOT commit `prepare-release.yml` creates, and path-filtered to the contract sources / spec / build plumbing), so `qnop-enterprise` can build against the latest development contract without waiting for a release. Neither workflow runs tests — `ci.yml` already gates every commit on `main`.

## Consequences

- **Easier:** `qnop-enterprise` can finally declare `io.qnop:qnop-spi` / `io.qnop:qnop-api-endpoint` dependencies and resolve them from GitHub Packages; the publication carries a correct, licence-bearing POM.
- **Harder / accepted:** consumers must authenticate to GitHub Packages even for these AGPL artifacts (a GitHub-Packages limitation — anonymous read is not supported); a future mirror to Maven Central would remove that friction if broader public consumption is wanted.
- **Deferred:** signing is inert until a `SIGNING_KEY` secret is added; a Maven Central mirror.

## Alternatives considered

- **Maven Central (Sonatype OSSRH).** Rejected for now: namespace verification, mandatory PGP signing, and a separate account — heavyweight for an artifact whose only consumer today is a sibling private repo. Revisit if public library consumption becomes a goal.
- **A private Maven repo (Nexus/Artifactory/S3).** Rejected: new infrastructure to run and secure, when GitHub Packages is already available and access-controlled by the existing org membership.
- **Committing the artifacts / a Git submodule of `qnop-spi`.** Rejected: no versioned dependency resolution, no transitive POM, and it couples the two repos' histories — the opposite of the clean published-contract boundary ADR-0003 wants.
- **Publishing `qnop-core` too.** Rejected: it is the AGPL application core, consumed as an image (ADR-0040), not a library; publishing it would invite linking that the open-core boundary (ADR-0002/0003) deliberately routes through the SPI.
