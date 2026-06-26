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

import java.util.Collection;

/**
 * Raised when a mail-template body references a {@code {{placeholder}}} outside the template's
 * closed set (issue #141). The web layer maps it to {@code 400 Bad Request}.
 */
public class MailTemplateValidationException extends RuntimeException {

  public MailTemplateValidationException(Collection<String> unknown, Collection<String> allowed) {
    super(
        "Unknown placeholder(s): "
            + format(unknown)
            + ". This template accepts only: "
            + format(allowed)
            + ".");
  }

  private static String format(Collection<String> names) {
    return names.stream().map(name -> "{{" + name + "}}").reduce((a, b) -> a + ", " + b).orElse("");
  }
}
