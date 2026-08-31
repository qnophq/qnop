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
 * Account-less review participation (issue #684, ADR-0061). {@link
 * io.qnop.spi.participant.ExternalParticipants} is a facade the <em>core implements</em> and an
 * add-on calls — the inverse of the other contracts — so an extension (guest links, qnop-ee#17) can
 * let someone without a user account take part in a review while the core keeps participation,
 * access checking, identity resolution and anonymity (ADR-0038) its own business. Pure contract: no
 * Spring, no persistence, no internal-module types — only the JDK.
 */
package io.qnop.spi.participant;
