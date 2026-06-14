# Architecture Decision Records

Important architecture decisions are recorded here as ADRs (see [ADR-0001](0001-record-architecture-decisions.md)). Each record is immutable once **Accepted**; to change a decision, add a new ADR that supersedes the old one (and update the old one's status).

## Status legend

- **Accepted** — decided and in force.
- **Proposed** — the direction is set, but details are deferred to a later phase. Not yet binding.
- **Superseded by ADR-NNNN** — replaced.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions as ADRs | Accepted |
| [0002](0002-open-core-via-polyrepo-and-published-spi.md) | Open-core distribution via polyrepo + published SPI artifact | Accepted |
| [0003](0003-agpl-boundary-is-the-spi.md) | The AGPL boundary is the SPI | Accepted |
| [0004](0004-layered-architecture-enforced-by-archunit.md) | Layered architecture with published contracts, enforced by ArchUnit | Accepted |
| [0005](0005-binary-documents-in-object-storage.md) | Binary documents in S3-compatible object storage | Accepted |
| [0006](0006-gradle-kotlin-dsl-jdk21.md) | Gradle Kotlin DSL, convention plugins, JDK 21 | Accepted |
| [0007](0007-spdx-dco-license-scanning.md) | SPDX headers, DCO, license scanning in CI | Accepted |
| [0008](0008-contribution-and-branching-workflow.md) | Contribution & branching workflow | Accepted |
| [0009](0009-multi-layer-annotation-anchoring.md) | Multi-layer annotation anchoring | Proposed |
| [0010](0010-docx-representation-strategy.md) | DOCX representation strategy | Proposed |
| [0011](0011-review-workflow-state-model.md) | Review workflow state model | Proposed |
| [0012](0012-edition-vs-entitlements.md) | Edition vs. entitlements / license gating | Proposed |
| [0013](0013-redis-and-search-deferred.md) | Redis & search index deferred | Proposed |
| [0014](0014-frontend-enterprise-separation.md) | Frontend enterprise separation | Proposed |
| [0015](0015-published-rest-api-contract-module.md) | Published REST API contract module (qnop-api) | Accepted |
| [0016](0016-contributor-license-agreement.md) | Contributor License Agreement enforced via CLA Assistant | Accepted |
| [0017](0017-renovate-dependency-automation.md) | Dependency automation via self-hosted Renovate + org-wide preset | Accepted |
| [0018](0018-main-branch-protection-ruleset.md) | main-branch protection via a repository ruleset (deferred) | Proposed |
| [0019](0019-source-copyright-headers.md) | Source files carry a copyright notice (devtank42 GmbH) | Accepted |
| [0020](0020-bootable-server-and-test-database.md) | Bootable server: PostgreSQL + Liquibase + JPA, tests on Testcontainers | Accepted |
| [0021](0021-openapi-first-contract-tooling.md) | OpenAPI-first REST contract tooling and the qnop-api submodule split | Accepted |
| [0022](0022-security-crypto-foundation.md) | Security & crypto foundation — layer placement and primitives | Accepted |
| [0023](0023-identity-and-access-model.md) | Identity & access model (schema level) | Accepted |
| [0024](0024-branding-asset-storage.md) | Branding-asset storage location (Postgres bytea) | Accepted |

## Template

```markdown
# ADR-NNNN: <title>

- **Status:** Proposed | Accepted | Superseded by ADR-NNNN
- **Date:** YYYY-MM-DD
- **Deciders:** <who>

## Context
What problem are we solving? What forces and constraints apply?

## Decision
The decision, stated plainly.

## Consequences
What becomes easier, what becomes harder, what we explicitly defer.

## Alternatives considered
What we rejected and why.
```
