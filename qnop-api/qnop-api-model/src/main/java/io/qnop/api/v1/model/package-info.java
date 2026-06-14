/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Published, Spring-free REST DTOs generated from {@code openapi.yaml} (ADR-0015, ADR-0021).
 *
 * <p>The OpenAPI generator emits the request/response types into this package at build time (under
 * {@code build/generated}); this hand-written {@code package-info} is the stable documentation
 * anchor for the published contract surface. The generated classes carry only Jackson and Jakarta
 * Bean Validation annotations — never Spring — which ArchUnit enforces.
 */
package io.qnop.api.v1.model;
