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

import org.springframework.stereotype.Component;

/**
 * Wraps a rendered HTML content fragment in a responsive, email-client-safe HTML document (issue
 * #19): a centered, max-width table layout with inline styles (the only CSS most mail clients
 * honor) and an optional call-to-action button. Template authors and the mail-sending flows (#20)
 * supply the inner content; this builder owns the shared chrome so every qnop email looks
 * consistent.
 *
 * <p>The caller is responsible for HTML-escaping any user-supplied values in {@code contentHtml}
 * (the Mustache HTML compiler in {@link MailTemplateService} escapes variables by default).
 */
@Component
public class EmailLayoutBuilder {

  /**
   * Builds the full HTML document.
   *
   * @param contentHtml the inner content fragment (already rendered + escaped)
   * @param ctaUrl optional call-to-action URL; when null no button is rendered
   * @param ctaText label for the call-to-action button (used only when {@code ctaUrl} is set)
   * @param footerLine a short footer line (e.g. the product name / a do-not-reply notice)
   */
  public String wrap(String contentHtml, String ctaUrl, String ctaText, String footerLine) {
    String button = ctaUrl == null || ctaUrl.isBlank() ? "" : ctaButton(ctaUrl, ctaText);
    return """
        <!DOCTYPE html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          </head>
          <body style="margin:0;padding:0;background:#f4f5f7;">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f5f7;">
              <tr>
                <td align="center" style="padding:32px 16px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                         style="max-width:560px;background:#ffffff;border-radius:12px;overflow:hidden;
                                font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
                    <tr>
                      <td style="padding:32px;color:#1a1a1a;font-size:15px;line-height:1.6;">
                        %s
                        %s
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:16px 32px 32px;color:#8a8f98;font-size:12px;line-height:1.5;">
                        %s
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
          </body>
        </html>
        """
        .formatted(contentHtml, button, footerLine);
  }

  private String ctaButton(String ctaUrl, String ctaText) {
    return """
        <table role="presentation" cellpadding="0" cellspacing="0" style="margin:24px 0;">
          <tr>
            <td align="center" style="border-radius:8px;background:#2d6cdf;">
              <a href="%s" style="display:inline-block;padding:12px 24px;color:#ffffff;
                 text-decoration:none;font-weight:600;font-size:15px;">%s</a>
            </td>
          </tr>
        </table>
        """
        .formatted(ctaUrl, ctaText == null ? "Open" : ctaText);
  }
}
