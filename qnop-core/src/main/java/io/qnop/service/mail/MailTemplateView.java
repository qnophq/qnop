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
 * A web-safe view of one template's effective content for the admin API: either a stored per-locale
 * row ({@code source = DATABASE}) or the built-in catalog fallback ({@code source = DEFAULT}). No
 * JPA entity leaks to the web layer (ADR-0004).
 */
public record MailTemplateView(
    String key,
    String locale,
    String subject,
    String bodyPlain,
    String bodyHtml,
    Source source,
    Instant updatedAt,
    String updatedBy) {

  /** Where the effective content came from. */
  public enum Source {
    /** A stored, admin-editable {@code mail_template} row. */
    DATABASE,
    /** The built-in {@link MailTemplateKey} fallback (no stored row). */
    DEFAULT
  }
}
