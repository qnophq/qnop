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
package io.qnop.service.oidc;

/**
 * Public, login-page projection of one enabled OIDC provider (issue #21). Carries only the
 * non-sensitive fields the SPA renders on a "Sign in with …" button, plus the derived
 * account-switch affordances. The web layer maps this straight onto the {@code
 * OidcProviderLoginInfo} DTO, so the entity (and its {@code OidcProviderType} enum) never leaks
 * past the service boundary (ADR-0004).
 *
 * @param accountPickerLoginUrl relative login URL that forces the upstream account picker (adds an
 *     allow-listed {@code prompt}); {@code null} when the provider cannot honour it (e.g. GitHub).
 * @param accountSwitchHintUrl absolute upstream sign-out URL used as the GitHub fallback; {@code
 *     null} otherwise.
 */
public record OidcProviderLoginView(
    String id,
    String name,
    String loginUrl,
    String iconKind,
    String accountPickerLoginUrl,
    String accountSwitchHintUrl) {}
