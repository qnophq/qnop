# ADR-0019: Source files carry a copyright notice (devtank42 GmbH)

- **Status:** Accepted
- **Date:** 2026-06-14
- **Deciders:** bigpuritz, devtank42 (with Claude)

## Context

[ADR-0007](0007-spdx-dco-license-scanning.md) requires every source file to carry an `SPDX-License-Identifier: AGPL-3.0-only` line, and the bare SPDX line was all the header contained. For an open-core product whose value rests on provable, enforceable copyright (and on the CLA's "Licensor", ADR-0016), each source file should also name the copyright holder explicitly — a "clean copyright" notice, not just a machine tag.

## Decision

Every source file carries a copyright notice for **`devtank42 GmbH`** (the qnop Licensor) alongside the SPDX identifier. The canonical block lives in the root **`license-header.txt`**:

```
/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 * ... GNU AGPL-3.0 recital ...
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
```

Application by file type:

| Type | Header | Enforcement |
| --- | --- | --- |
| Java (`.java`) | full block from `license-header.txt` | Spotless `licenseHeaderFile` (auto-applied on `spotlessApply`, checked by `spotlessCheck`) |
| Frontend (`.ts`, `.tsx`, `.css`) | full block (`/* */`) | prettier-compatible; manual for now |
| Gradle Kotlin DSL (`.gradle.kts`) | two `//` lines: copyright + SPDX | manual (build scripts are not Spotless-managed) |
| Config (`.yml`, `.json`, `.properties`) | SPDX where present (YAML); no copyright recital | CI `license-headers` grep for `.kts`/`.java` |

The copyright holder and year (`2026-present`) are hardcoded — no `$YEAR` ratchet — matching the `plugwerk` convention this project mirrors.

### package-info.java caveat

Spotless's `spotlessApply` does **not** auto-insert the header into `package-info.java` (its delimiter logic treats the package Javadoc as content), though `spotlessCheck` accepts a correctly-placed block. The existing `package-info.java` files were given the block manually. New ones must add it by hand; the CI `license-headers` job still guarantees the SPDX line is present on every `.java`/`.kts`.

## Consequences

- Easier: the copyright owner is named in every source file; provenance is unambiguous for relicensing and audits. The header is one file (`license-header.txt`) to change if the holder/year ever moves.
- Harder: a slightly larger per-file header; `package-info.java` and frontend headers are not auto-inserted (documented above).
- This **complements** ADR-0007 (it keeps the SPDX line); it does not supersede it.

## Alternatives considered

- **Keep the bare SPDX line only.** Rejected — does not name the copyright holder, which the open-core/CLA model wants explicit.
- **Concise two-line `// SPDX-FileCopyrightText` + `// SPDX-License-Identifier` everywhere (REUSE style).** Cleaner to enforce uniformly (works on `package-info.java` too), but does not match the fuller `plugwerk` header the project is aligning with. Rejected for the verbose block on real source; the two-line form is used only for build scripts.
- **Adopt plugwerk's Apache-2.0 header for the published contracts (`qnop-spi`, `qnop-api`).** Deliberately **not** done here: ADR-0003/0007 keep all modules AGPL-3.0-only. Relicensing the contracts permissively is a separate business decision that would need its own ADR.
