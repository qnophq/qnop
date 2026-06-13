# ADR-0003: The AGPL boundary is the SPI

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

Given the open-core model ([ADR-0002](0002-open-core-via-polyrepo-and-published-spi.md)), the core must be able to *consume* commercial behavior without *knowing about* or compiling against it. We need a single, explicit technical seam.

## Decision

- `qnop-spi` contains **pure interfaces and DTOs only** — the extension points (e.g. `AiReviewerProvider`, `AnnotationSummarizer`, `DuplicateAnnotationDetector`, `StorageProvider`, `TextExtractor`, `DocumentConverter`, `EditionResolver`). No logic.
- The core ships a **Community default** for each SPI, wired via Spring `@ConditionalOnMissingBean` (often a no-op or base implementation).
- Enterprise modules provide real implementations through a Spring `@AutoConfiguration` registered in `META-INF/spring/...AutoConfiguration.imports`. **Presence of the enterprise JAR on the classpath = enterprise edition.** No edition branch exists in core code.
- Spring **profiles are not the edition mechanism** — they remain for environment differences (`dev`/`prod`/`saas`) only.

## Consequences

- Composition is by classpath, not by `if (edition == ...)`. The core never references commercial types.
- Enterprise modules are "just another implementation" of a published interface — consistent with the layered architecture ([ADR-0004](0004-layered-architecture-enforced-by-archunit.md)).
- The SPI is a public API surface: it must be designed and versioned deliberately.

## Alternatives considered

- **Spring profiles toggling beans in one build** — rejected: implies both editions in one artifact, defeating the license boundary.
- **Hard-coded feature flags in core** — rejected: core would have to know commercial features exist.
