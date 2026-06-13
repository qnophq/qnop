# ADR-0004: Hexagonal architecture enforced by ArchUnit

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

The SPI boundary ([ADR-0003](0003-agpl-boundary-is-the-spi.md)) only works if the core itself has clean, directional module boundaries: the domain must be framework-free, and the extension points must be a genuine subset of the application's ports. Boundaries that are only documented tend to erode.

## Decision

Adopt ports-and-adapters (hexagonal) with these modules and the dependency direction:

```
qnop-spi          (no dependencies; consumable by all)
qnop-domain       → spi            (framework-free entities, VOs, workflow state machine)
qnop-application  → domain         (use cases; defines ports as interfaces)
qnop-persistence  → application    (JPA adapter)
qnop-storage      → application    (S3 adapter)
qnop-document     → application    (extraction/conversion/anchoring adapter)
qnop-security     → application    (authn/authz adapter)
qnop-web          → application    (REST adapter)
qnop-bootstrap          → all adapters   (composition root; the only wiring point)
```

The SPI is the subset of ports that may be implemented commercially. These directions are **enforced in CI by an ArchUnit test** in `qnop-bootstrap` (`ArchitectureRulesTest`), including a rule that the domain depends on no framework (`org.springframework..`, `jakarta.persistence..`) or adapter package.

## Consequences

- The domain stays portable and unit-testable without Spring/JPA.
- Violations fail the build, not a review comment.
- In Phase 0 the modules hold only `package-info` placeholders, so the rules pass trivially; they bite as code lands in Phase 1.

## Alternatives considered

- **Conventional layered packages in one module** — rejected: doesn't give the publishable SPI artifact or the enforced boundary.
- **Documentation-only boundaries** — rejected: erode without automated enforcement.
