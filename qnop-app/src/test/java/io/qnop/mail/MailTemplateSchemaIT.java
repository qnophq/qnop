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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.MailTemplate;
import io.qnop.repository.MailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the mail-template schema (issue #14) against a real PostgreSQL (ADR-0020): the English
 * seed templates are applied by Liquibase, the {@code (template_key, locale)} uniqueness holds, the
 * key lookup backs the locale fallback chain, and new rows get a generated UUIDv7. Each test runs
 * in a rolled-back transaction. Requires Docker.
 */
@Transactional
class MailTemplateSchemaIT extends AbstractIntegrationTest {

  @Autowired MailTemplateRepository templates;

  @Test
  void seedsEnglishAuthTemplates() {
    assertThat(templates.findByTemplateKeyAndLocale("auth.registration_verification", "en"))
        .hasValueSatisfying(
            t -> {
              assertThat(t.getSubject()).contains("{{siteName}}");
              // Unified vocabulary that the send flows actually supply (issue #140).
              assertThat(t.getBodyPlain()).contains("{{recipientName}}", "{{actionUrl}}");
              // Seeds carry plain text only; the branded HTML is built by EmailLayoutBuilder.
              assertThat(t.getBodyHtml()).isNull();
            });
    assertThat(templates.findByTemplateKeyAndLocale("auth.password_reset", "en"))
        .hasValueSatisfying(t -> assertThat(t.getBodyPlain()).contains("{{actionUrl}}"));
    assertThat(templates.findByTemplateKeyAndLocale("auth.admin_password_reset", "en")).isPresent();
    // Review notification templates (issue #316) — seeded plain, HTML from the branded chrome.
    for (String key :
        new String[] {
          "review.participant_added",
          "review.annotation_created",
          "review.annotation_decided",
          "review.comment_added",
          "review.workflow_changed"
        }) {
      assertThat(templates.findByTemplateKeyAndLocale(key, "en"))
          .hasValueSatisfying(
              t -> {
                assertThat(t.getBodyPlain()).contains("{{recipientName}}", "{{actionUrl}}");
                assertThat(t.getSubject()).contains("{{documentTitle}}");
                assertThat(t.getBodyHtml()).isNull();
              });
    }
  }

  @Test
  void enforcesKeyLocaleUniqueness() {
    // auth.registration_verification/en already exists from the seed.
    MailTemplate duplicate =
        new MailTemplate("auth.registration_verification", "en", "Dup", "body");

    assertThatThrownBy(() -> templates.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findByTemplateKeyReturnsEveryLocaleVariant() {
    templates.saveAndFlush(
        new MailTemplate("auth.password_reset", "de", "Passwort zuruecksetzen", "Hallo"));

    assertThat(templates.findByTemplateKey("auth.password_reset"))
        .extracting(MailTemplate::getLocale)
        .contains("en", "de");
  }

  @Test
  void persistsNewTemplateWithGeneratedUuidV7() {
    MailTemplate saved =
        templates.saveAndFlush(new MailTemplate("test.sample", "en", "Subject", "Body"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).isEqualTo(7);
    assertThat(saved.getUpdatedAt()).isNotNull();
  }
}
