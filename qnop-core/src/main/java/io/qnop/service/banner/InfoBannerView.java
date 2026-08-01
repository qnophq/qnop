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
package io.qnop.service.banner;

/**
 * One resolved banner (issue #664): what to say, how loudly, and where to send anyone who wants
 * more.
 *
 * @param severity {@code info}, {@code warning} or {@code critical} — the registry's enum options
 * @param message the notice, plain text and already stripped
 * @param linkLabel the link's label, or {@code null} when the banner carries no link
 * @param linkUrl an {@code http(s)} URL (validated on write), or {@code null} with no link
 */
public record InfoBannerView(String severity, String message, String linkLabel, String linkUrl) {}
