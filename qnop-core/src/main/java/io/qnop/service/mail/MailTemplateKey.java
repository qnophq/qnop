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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Bodies are logic-less Mustache; variables ({@code {{siteName}}}, {@code {{recipientName}}},
 * {@code {{actionUrl}}}, {@code {{expiresAtHuman}}}) are supplied at render time and must match the
 * variables the live send flows pass (issue #140). Each key carries a plain-text body and an inner
 * HTML content fragment; {@link EmailLayoutBuilder} wraps the fragment in the shared branded chrome
 * unless a stored row overrides the HTML.
 */
public enum MailTemplateKey {
  REGISTRATION_VERIFICATION(
      "auth.registration_verification",
      "Account verification",
      "Verify your {{siteName}} account",
      """
      Hi {{recipientName}},

      Confirm your email address to activate your {{siteName}} account:

      {{actionUrl}}

      This link expires {{expiresAtHuman}}. If you did not create this account, you can safely ignore this message.
      """,
      """
      <h1 style="margin:0 0 14px;color:#18191f;font-size:22px;font-weight:700;letter-spacing:-0.01em;line-height:1.3;">Confirm your email</h1>
      <p style="margin:0 0 14px;">Hi {{recipientName}},</p>
      <p style="margin:0 0 14px;">Confirm this address to activate your <strong style="color:#18191f;">{{siteName}}</strong> account.</p>
      <p style="margin:0;color:#9a9ea8;font-size:13px;">This link expires {{expiresAtHuman}}. If you didn't create this account, you can ignore this email.</p>
      """,
      "Verify email address",
      "Confirm your email to activate your {{siteName}} account.",
      "siteName",
      "recipientName",
      "actionUrl",
      "expiresAtHuman"),
  PASSWORD_RESET(
      "auth.password_reset",
      "Password reset",
      "Reset your {{siteName}} password",
      """
      Hi {{recipientName}},

      We received a request to reset your {{siteName}} password. Choose a new one here:

      {{actionUrl}}

      This link expires {{expiresAtHuman}}. If you did not request this, you can safely ignore this message; your password stays unchanged.
      """,
      """
      <h1 style="margin:0 0 14px;color:#18191f;font-size:22px;font-weight:700;letter-spacing:-0.01em;line-height:1.3;">Reset your password</h1>
      <p style="margin:0 0 14px;">Hi {{recipientName}},</p>
      <p style="margin:0 0 14px;">We received a request to reset your <strong style="color:#18191f;">{{siteName}}</strong> password. Choose a new one below.</p>
      <p style="margin:0;color:#9a9ea8;font-size:13px;">This link expires {{expiresAtHuman}}. If you didn't request this, ignore this email — your password stays unchanged.</p>
      """,
      "Choose a new password",
      "Reset your {{siteName}} password.",
      "siteName",
      "recipientName",
      "actionUrl",
      "expiresAtHuman"),
  ADMIN_PASSWORD_RESET(
      "auth.admin_password_reset",
      "Admin password reset",
      "Your {{siteName}} password was reset by an administrator",
      """
      Hi {{recipientName}},

      An administrator reset your {{siteName}} password. Set a new one to sign back in:

      {{actionUrl}}

      This link expires {{expiresAtHuman}}.
      """,
      """
      <h1 style="margin:0 0 14px;color:#18191f;font-size:22px;font-weight:700;letter-spacing:-0.01em;line-height:1.3;">Set a new password</h1>
      <p style="margin:0 0 14px;">Hi {{recipientName}},</p>
      <p style="margin:0 0 14px;">An administrator reset your <strong style="color:#18191f;">{{siteName}}</strong> password. Set a new one to sign back in.</p>
      <p style="margin:0;color:#9a9ea8;font-size:13px;">This link expires {{expiresAtHuman}}.</p>
      """,
      "Set a new password",
      "An administrator reset your {{siteName}} password.",
      "siteName",
      "recipientName",
      "actionUrl",
      "expiresAtHuman");

  /**
   * Representative demo value per known placeholder, used to prefill the preview's sample-variable
   * editor (issue #141). Every placeholder a template declares must have an entry here.
   */
  private static final Map<String, String> DEMO_VALUES =
      Map.of(
          "siteName", "qnop",
          "recipientName", "Jane Doe",
          "actionUrl", "https://qnop.example/action?token=SAMPLE",
          "expiresAtHuman", "in 30 minutes");

  private final String key;
  private final String friendlyName;
  private final String defaultSubject;
  private final String defaultBodyPlain;
  private final String defaultBodyHtmlContent;
  private final String ctaLabel;
  private final String preheader;
  private final List<String> placeholders;

  MailTemplateKey(
      String key,
      String friendlyName,
      String defaultSubject,
      String defaultBodyPlain,
      String defaultBodyHtmlContent,
      String ctaLabel,
      String preheader,
      String... placeholders) {
    this.key = key;
    this.friendlyName = friendlyName;
    this.defaultSubject = defaultSubject;
    this.defaultBodyPlain = defaultBodyPlain;
    this.defaultBodyHtmlContent = defaultBodyHtmlContent;
    this.ctaLabel = ctaLabel;
    this.preheader = preheader;
    this.placeholders = Arrays.stream(placeholders).sorted().toList();
  }

  /** The stable storage key (matches the {@code template_key} column). */
  public String key() {
    return key;
  }

  /** A human-readable label for the template (e.g. {@code Password reset}). */
  public String friendlyName() {
    return friendlyName;
  }

  /** The sorted, closed set of placeholder names this template accepts. */
  public List<String> placeholders() {
    return placeholders;
  }

  /** A placeholder → representative demo value map for this template's placeholders. */
  public Map<String, String> sampleVars() {
    Map<String, String> vars = new LinkedHashMap<>();
    for (String placeholder : placeholders) {
      vars.put(placeholder, DEMO_VALUES.getOrDefault(placeholder, ""));
    }
    return vars;
  }

  public String defaultSubject() {
    return defaultSubject;
  }

  public String defaultBodyPlain() {
    return defaultBodyPlain;
  }

  /**
   * The inner HTML content fragment (heading + body), wrapped in the shared branded chrome by
   * {@link EmailLayoutBuilder} when no stored HTML override exists. Logic-less Mustache,
   * HTML-escaped.
   */
  public String defaultBodyHtmlContent() {
    return defaultBodyHtmlContent;
  }

  /** The call-to-action button label for this template. */
  public String ctaLabel() {
    return ctaLabel;
  }

  /** The hidden inbox-preview line (Mustache). */
  public String preheader() {
    return preheader;
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
