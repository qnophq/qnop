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
package io.qnop.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.MailTemplate;
import io.qnop.repository.MailTemplateRepository;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.MailTemplateService;
import io.qnop.service.mail.RenderedMail;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression for issue #140: every seeded {@code mail_template} row must render against exactly the
 * variable set its live send flow supplies — no missing-variable {@code MustacheException}. The
 * seeds and the flow vocabulary drifted apart historically ({@code username}/{@code resetLink} vs
 * {@code recipientName}/{@code actionUrl}), so strict Mustache rendering threw and every seeded
 * template silently became {@code SendResult.Failed}. Runs against the real migrated schema
 * (Testcontainers); requires Docker.
 */
class SeededMailTemplateRenderIT extends AbstractIntegrationTest {

  /** Exactly the variables the auth flows (#140) and review notifications (#316) supply. */
  private static final Map<String, Object> FLOW_VARS =
      Map.ofEntries(
          Map.entry("siteName", "qnop"),
          Map.entry("recipientName", "Jane Doe"),
          Map.entry("actionUrl", "https://qnop.example/action?token=abc123"),
          Map.entry("expiresAtHuman", "in 30 minutes"),
          Map.entry("actorName", "Alex Reviewer"),
          Map.entry("documentTitle", "Q3 contract draft"),
          Map.entry("annotationExcerpt", "The liability clause needs a cap."),
          Map.entry("commentExcerpt", "Agreed — let's cap it at 12 months."),
          Map.entry("decision", "resolved"),
          Map.entry("versionNumber", "3"),
          Map.entry("oldState", "In review"),
          Map.entry("newState", "Changes requested"));

  @Autowired MailTemplateRepository templates;
  @Autowired MailTemplateService templateService;

  @Test
  void everySeededRowRendersAgainstItsFlowVariableSet() {
    var rows = templates.findAll();
    assertThat(rows).as("seeded mail_template rows").isNotEmpty();

    for (MailTemplate row : rows) {
      MailTemplateKey key =
          MailTemplateKey.fromKey(row.getTemplateKey())
              .orElseThrow(
                  () -> new AssertionError("seeded unknown template key: " + row.getTemplateKey()));

      assertThatCode(() -> templateService.render(key, FLOW_VARS, row.getLocale()))
          .as("rendering seeded row %s/%s", row.getTemplateKey(), row.getLocale())
          .doesNotThrowAnyException();

      RenderedMail mail = templateService.render(key, FLOW_VARS, row.getLocale());
      assertThat(mail.subject()).isNotBlank();
      assertThat(mail.bodyPlain()).contains("Jane Doe");
      if (row.getTemplateKey().startsWith("auth.")) {
        assertThat(mail.bodyPlain()).contains("in 30 minutes");
      }
      // The branded HTML chrome is built by EmailLayoutBuilder for every template.
      assertThat(mail.bodyHtml()).isNotNull().contains("<!DOCTYPE html>").contains("qnop");
    }
  }
}
