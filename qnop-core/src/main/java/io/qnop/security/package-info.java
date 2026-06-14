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
 * Security &amp; crypto foundation shared by all auth work (issue #10, ADR-0022).
 *
 * <p>This package is framework-free of the web layer: it holds validated configuration properties
 * ({@link io.qnop.security.QnopProperties}), password hashing and symmetric text encryption beans
 * ({@link io.qnop.security.CryptoConfiguration}), HKDF-SHA256 key derivation ({@link
 * io.qnop.security.Hkdf}, {@link io.qnop.security.JwtKeyService}). The servlet {@code
 * SecurityFilterChain} lives in {@code io.qnop.web.security} (qnop-app) — see ADR-0022 for why the
 * crypto foundation and the filter chain are split across modules while sharing the layered
 * architecture (ADR-0004).
 *
 * <p>ArchUnit treats {@code io.qnop.security} as its own layer, accessible only by the service and
 * web layers.
 */
package io.qnop.security;
