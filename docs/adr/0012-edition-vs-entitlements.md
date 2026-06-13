# ADR-0012: Edition vs. entitlements / license gating

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

Beyond *which* edition is running (Community vs. Enterprise), the commercial edition must gate individual paid features per customer (e.g. `ai.reviewer`, `ai.summary`, seat limits).

## Decision (direction — to be finalized when commercial features land)

- **Edition** is determined by the classpath ([ADR-0003](0003-agpl-boundary-is-the-spi.md)): the presence of enterprise JARs. This is the honest source of truth.
- **Entitlements** are carried in a **signed license token** (e.g. JWS with an asymmetric signature; public key shipped in the enterprise module). A `LicenseProvider` reads entitlements and gates individual beans/endpoints.
- The licensing/gating logic lives in the **enterprise module**, never in the AGPL core (core must not know about commercial gating).
- An `/api/edition` endpoint exposes edition + active entitlements so the frontend can flag features. (In Phase 0 the frontend shows a static `Community` chip; the endpoint arrives with the bootable server.)
- For watertight separation, the riskiest features (AI) should be able to run as a **separate process** (REST/gRPC) — the SPI is shaped to make that move cheap.

## Status note

Recorded now to fix the gating approach before any commercial code exists. Implementation is out of scope until the enterprise repo and bootable server exist. Obtain IP-counsel review of the AGPL boundary before first commercial release.
