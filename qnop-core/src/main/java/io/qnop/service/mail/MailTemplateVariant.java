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
package io.qnop.service.mail;

import java.time.Instant;

/**
 * The status of one template in one language the editor offers (issue #853): whether an admin
 * customised it, whether qnop ships a default in that language at all (when not, and nothing is
 * customised, the mail goes out from the English default), and the effective subject.
 *
 * @param locale the language
 * @param source whether a stored row or the built-in default is effective
 * @param shipped whether qnop ships a built-in default in this language
 * @param instanceDefault whether this is the configured default language
 * @param subject the effective subject in this language
 * @param updatedAt when the row was last saved; {@code null} for a built-in default
 * @param updatedByName the saving admin's display name, if known
 */
public record MailTemplateVariant(
    String locale,
    MailTemplateView.Source source,
    boolean shipped,
    boolean instanceDefault,
    String subject,
    Instant updatedAt,
    String updatedByName) {}
