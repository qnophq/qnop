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

/**
 * The built-in content of one mail template in one language (issue #853): what a template renders
 * when no admin has customised it. English comes from {@link MailTemplateKey}; every other language
 * from a shipped resource loaded by {@link MailTemplateDefaults}.
 *
 * @param locale the language this content is written in (its layout chrome follows it)
 * @param subject the subject line (Mustache)
 * @param bodyPlain the plain-text body (Mustache)
 * @param bodyHtmlContent the inner HTML fragment the branded layout wraps (Mustache, HTML-escaped)
 * @param ctaLabel the call-to-action button label; {@code null} for a template without one
 * @param preheader the hidden inbox-preview line (Mustache)
 */
public record MailTemplateContent(
    String locale,
    String subject,
    String bodyPlain,
    String bodyHtmlContent,
    String ctaLabel,
    String preheader) {

  /** The English catalog content of {@code key}. */
  public static MailTemplateContent english(MailTemplateKey key) {
    return new MailTemplateContent(
        MailLocaleResolver.FALLBACK_LOCALE,
        key.defaultSubject(),
        key.defaultBodyPlain(),
        key.defaultBodyHtmlContent(),
        key.ctaLabel(),
        key.preheader());
  }
}
