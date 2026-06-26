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
 * Builds the shared, branded HTML chrome around a rendered content fragment (issue #19/#140), so
 * every transactional qnop email looks consistent. The design is a light "editorial document"
 * aesthetic — warm paper background, ink-dark type, a single confident indigo accent, the {@code
 * qnop·} wordmark, a prominent call-to-action and a monospace fallback link.
 *
 * <p>Email-client-safe by construction: a centered max-width table layout with inline styles only
 * (the only CSS most clients honor), declared {@code light} color-scheme so clients do not
 * auto-invert it badly, and a hidden preheader for the inbox preview line.
 *
 * <p>The {@code contentHtml} fragment is expected to be already rendered and HTML-escaped (the
 * Mustache HTML compiler in {@link MailTemplateService} escapes variables by default). The {@code
 * brandName}, {@code ctaUrl}, {@code ctaText}, {@code preheader} and {@code footerLine} values are
 * escaped here.
 */
@Component
public class EmailLayoutBuilder {

  private static final String ACCENT = "#2b43d0";
  private static final String INK = "#18191f";
  private static final String INK_SOFT = "#3c3f49";
  private static final String MUTED = "#9a9ea8";
  private static final String PAPER = "#eceae3";
  private static final String HAIRLINE = "#ece9e1";
  private static final String BODY_FONT =
      "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
  private static final String MONO_FONT =
      "'SFMono-Regular',ui-monospace,Menlo,Consolas,'Liberation Mono',monospace";

  /**
   * Wraps a content fragment in the full branded HTML document.
   *
   * @param brandName the product/site name shown as the wordmark (defaults to {@code qnop})
   * @param preheader the hidden inbox-preview line
   * @param contentHtml the inner content fragment (already rendered + escaped)
   * @param ctaUrl optional call-to-action URL; when blank no button or fallback link is rendered
   * @param ctaText label for the call-to-action button
   * @param footerLine a short footer line (e.g. a do-not-reply notice)
   */
  public String wrap(
      String brandName,
      String preheader,
      String contentHtml,
      String ctaUrl,
      String ctaText,
      String footerLine) {
    String brand = brandName == null || brandName.isBlank() ? "qnop" : brandName;
    String cta = ctaUrl == null || ctaUrl.isBlank() ? "" : ctaBlock(ctaUrl, ctaText);
    return """
        <!DOCTYPE html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <meta name="color-scheme" content="light only" />
            <meta name="supported-color-schemes" content="light" />
          </head>
          <body style="margin:0;padding:0;background:%s;-webkit-font-smoothing:antialiased;">
            <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:%s;font-size:1px;line-height:1px;">%s</div>
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:%s;">
              <tr>
                <td align="center" style="padding:40px 16px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                         style="max-width:560px;width:100%%;background:#ffffff;border:1px solid #e6e3db;
                                border-radius:16px;overflow:hidden;font-family:%s;">
                    <tr><td style="height:4px;background:%s;font-size:0;line-height:0;">&nbsp;</td></tr>
                    <tr>
                      <td style="padding:30px 36px 0;">
                        <span style="font-size:19px;font-weight:700;letter-spacing:-0.02em;color:%s;">%s<span style="color:%s;">.</span></span>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 36px 8px;color:%s;font-size:15px;line-height:1.65;">
                        %s
                        %s
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:8px 36px 32px;">
                        <div style="border-top:1px solid %s;padding-top:18px;color:%s;font-size:12px;line-height:1.6;">%s</div>
                      </td>
                    </tr>
                  </table>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%%;">
                    <tr><td style="padding:16px 36px 0;text-align:center;color:#b3b6bd;font-size:11px;letter-spacing:0.04em;">%s · Qualified Notes on Papers</td></tr>
                  </table>
                </td>
              </tr>
            </table>
          </body>
        </html>
        """
        .formatted(
            PAPER,
            PAPER,
            escape(preheader),
            PAPER,
            BODY_FONT,
            ACCENT,
            INK,
            escape(brand),
            ACCENT,
            INK_SOFT,
            contentHtml,
            cta,
            HAIRLINE,
            MUTED,
            escape(footerLine),
            escape(brand));
  }

  private String ctaBlock(String ctaUrl, String ctaText) {
    String label = ctaText == null || ctaText.isBlank() ? "Open" : ctaText;
    String url = escape(ctaUrl);
    return """
        <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:26px 0 6px;">
          <tr>
            <td style="border-radius:10px;background:%s;box-shadow:0 1px 2px rgba(20,28,90,0.25);">
              <a href="%s" style="display:inline-block;padding:13px 28px;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;letter-spacing:0.01em;">%s</a>
            </td>
          </tr>
        </table>
        <p style="margin:16px 0 0;color:%s;font-size:12px;line-height:1.6;">Button not working? Paste this link into your browser:</p>
        <p style="margin:6px 0 0;">
          <a href="%s" style="color:%s;font-size:12px;word-break:break-all;font-family:%s;text-decoration:none;">%s</a>
        </p>
        """
        .formatted(ACCENT, url, escape(label), MUTED, url, ACCENT, MONO_FONT, url);
  }

  /** Minimal HTML-attribute/text escaping for the non-fragment values placed into the chrome. */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
