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
package io.qnop.spi.mention;

import java.util.UUID;

/**
 * What a {@link MentionResolver} knows about the comment whose token it resolves (issue #598).
 *
 * @param documentId the review (document) the comment belongs to
 * @param ownerId the review owner's user id
 * @param authorId the user id of the comment's author — a resolver may exclude self-mentions
 */
public record MentionContext(UUID documentId, UUID ownerId, UUID authorId) {}
