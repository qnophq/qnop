# ADR-0014: Frontend enterprise separation

- **Status:** Accepted — finalized by [ADR-0039](0039-enterprise-packaging-and-runtime-extensions.md)
- **Date:** 2026-06-13
- **Deciders:** qnop core team

## Context

AGPL-3.0 extends to the JavaScript bundle shipped to the browser. The open-core boundary ([ADR-0002](0002-open-core-via-polyrepo-and-published-spi.md), [ADR-0003](0003-agpl-boundary-is-the-spi.md)) must therefore also exist on the frontend, not only in the backend — otherwise commercial UI would be distributed under AGPL.

## Decision (direction — to be finalized when enterprise UI is built)

- The open-source frontend (this repo's `qnop-ui/`) is AGPL and defines **plugin slots / extension points** for edition-specific UI (e.g. an AI-review panel mount).
- Enterprise UI components live in a **separate private npm package** in the `qnop-enterprise` repository and are composed into the commercial frontend build, mirroring the backend SPI model.
- Feature visibility is driven at runtime by the entitlements from `/api/edition` ([ADR-0012](0012-edition-vs-entitlements.md)).

## Status note

Recorded now because it's an easily-forgotten consequence of AGPL on shipped JS. No enterprise UI exists yet; the slot mechanism is designed when the first edition-specific component is needed. Include the frontend boundary in the IP-counsel review.

## Amendment (2026-07-16)

The private-npm-package bullet was finalized differently: enterprise UI plugs in via runtime ESM extensions per [ADR-0039](0039-enterprise-packaging-and-runtime-extensions.md) §3; build-time composition of a private npm package remains the documented fallback.
