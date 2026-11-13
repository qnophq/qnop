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
package io.qnop.service;

import java.util.UUID;

/**
 * A lead invited a user into a team (issue #948). Published inside the inviting transaction and
 * heard after it commits, so a rolled-back invitation notifies nobody.
 *
 * @param teamId the team the user is invited into
 * @param userId the invited user
 * @param actorId the lead who sent the invitation
 */
public record TeamInvitationCreated(UUID teamId, UUID userId, UUID actorId) {}
