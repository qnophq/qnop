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
 * Mention-resolution extension point (issue #598, ADR-0058). A {@link
 * io.qnop.spi.mention.MentionResolver} turns one {@code @slug} token of a comment into the user ids
 * it addresses; the Community default resolves user profile slugs in {@code
 * io.qnop.service.review}, and an add-on may contribute further principals (a team's members, for
 * example). Pure contract: no Spring, no persistence, no internal-module types — only the JDK.
 */
package io.qnop.spi.mention;
