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
package io.qnop.spi.auth;

import java.util.Set;

/**
 * The result of authenticating a machine credential (issue #686, ADR-0060).
 *
 * @param subject stable identifier of the machine actor — an opaque string, recommended to carry
 *     the contributing extension's namespace (e.g. {@code svc:reporting}). It must not parse as a
 *     UUID: user subjects are UUIDs, and the core rejects a contributed principal that could be
 *     mistaken for one.
 * @param scopes the extension's own authorization vocabulary; the core exposes each as an {@code
 *     EXT_}-prefixed authority, never as a human role — a contributed principal can never satisfy
 *     an {@code ADMIN}/{@code MEMBER}/{@code AUDITOR} gate.
 */
public record MachinePrincipal(String subject, Set<String> scopes) {

  public MachinePrincipal {
    scopes = Set.copyOf(scopes);
  }
}
