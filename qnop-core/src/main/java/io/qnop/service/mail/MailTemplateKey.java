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

import java.util.Optional;

/**
 * The catalog of known mail templates (issue #19). Each key carries built-in default content used
 * as the final fallback when no per-locale row exists in the {@code mail_template} table (issue
 * #14). The stable {@link #key()} string matches the {@code template_key} column and the seeded
 * rows; the DB row, when present, always wins over these defaults.
 *
 * <p>This is a separate type from the {@code io.qnop.entity.MailTemplate} JPA entity (the DB row):
 * the enum is the authoritative <em>registry</em> of which templates exist, the entity is one
 * stored per-locale override.
 *
 * <p>Bodies are logic-less Mustache; variables (e.g. {@code {{siteName}}}, {@code {{actionUrl}}})
 * are supplied at render time. HTML defaults are intentionally absent here — the seeded rows carry
 * the rich responsive HTML; when neither exists the message is sent plain-text only.
 */
public enum MailTemplateKey {
  REGISTRATION_VERIFICATION(
      "auth.registration_verification",
      "Verify your {{siteName}} account",
      """
      Hi {{recipientName}},

      Please confirm your email address to activate your {{siteName}} account:

      {{actionUrl}}

      If you did not create this account, you can safely ignore this message.
      """),
  PASSWORD_RESET(
      "auth.password_reset",
      "Reset your {{siteName}} password",
      """
      Hi {{recipientName}},

      We received a request to reset your {{siteName}} password. Use the link below to choose a new one:

      {{actionUrl}}

      If you did not request this, you can safely ignore this message; your password stays unchanged.
      """),
  ADMIN_PASSWORD_RESET(
      "auth.admin_password_reset",
      "Your {{siteName}} password was reset by an administrator",
      """
      Hi {{recipientName}},

      An administrator has reset your {{siteName}} password. Use the link below to set a new one:

      {{actionUrl}}
      """);

  private final String key;
  private final String defaultSubject;
  private final String defaultBodyPlain;

  MailTemplateKey(String key, String defaultSubject, String defaultBodyPlain) {
    this.key = key;
    this.defaultSubject = defaultSubject;
    this.defaultBodyPlain = defaultBodyPlain;
  }

  /** The stable storage key (matches the {@code template_key} column). */
  public String key() {
    return key;
  }

  public String defaultSubject() {
    return defaultSubject;
  }

  public String defaultBodyPlain() {
    return defaultBodyPlain;
  }

  /** Resolves a storage key back to its catalog entry, if known. */
  public static Optional<MailTemplateKey> fromKey(String key) {
    for (MailTemplateKey value : values()) {
      if (value.key.equals(key)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
