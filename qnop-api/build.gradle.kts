// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// qnop-api — container project for the PUBLISHED REST API contract (ADR-0015,
// ADR-0021). It builds nothing itself; it only holds the single source-of-truth
// OpenAPI spec at src/main/resources/openapi/openapi.yaml. Two submodules
// generate from that spec:
//   - qnop-api-model    : pure, Spring-free DTOs (the external stability surface)
//   - qnop-api-endpoint : Spring MVC interfaces implemented by qnop-app
//
// No plugins are applied here on purpose (no sources to compile/format).
