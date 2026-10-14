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

import java.util.List;

/**
 * Raised when a template is read, saved or reset in a language the editor does not offer (issue
 * #853): a row in such a language would never be sent, because a recipient's preference counts only
 * for a shipped language ({@link MailLocaleResolver}). The web layer maps it to {@code 400 Bad
 * Request}.
 */
public class UnsupportedMailLocaleException extends RuntimeException {

  public UnsupportedMailLocaleException(String locale, List<String> offered) {
    super(
        "Mail templates are not offered in '"
            + locale
            + "'. Supported languages: "
            + String.join(", ", offered)
            + ".");
  }
}
