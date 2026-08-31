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
 * Machine-credential authentication extension point (issue #686, ADR-0060). A {@link
 * io.qnop.spi.auth.BearerCredentialAuthenticator} lets an add-on authenticate a bearer credential
 * that is not a qnop user token — a service-account key, say — against the existing API surface.
 * The core keeps every security-relevant decision: where in the chain authentication happens, how
 * failures render, that a contributed principal can never be a user and never carries a human role.
 * Pure contract: no Spring, no persistence, no internal-module types — only the JDK.
 */
package io.qnop.spi.auth;
