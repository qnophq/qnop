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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.NotificationRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.UserSettingKey;
import io.qnop.service.auth.PasswordResetFlowService;
import io.qnop.service.auth.RegistrationService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.MailTemplateService;
import io.qnop.service.mail.RenderedMail;
import io.qnop.service.notification.NotificationDigestService;
import io.qnop.testsupport.SeededIntegrationTest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Every sender path writes in the recipient's language (issue #853), end to end: the real resolver
 * and the real variables. {@link MailService} is mocked like in the other mail ITs (sharing their
 * application context); each captured call — template, variables and language — is then rendered
 * through the real {@link MailTemplateService} with the shipped German defaults, which is exactly
 * what {@code MailService.sendMailFromTemplate} does before handing off to SMTP.
 */
class LocalizedMailIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @MockitoBean private MailService mail;

  @Autowired private MailTemplateService templates;
  @Autowired private ApplicationSettingsService settings;
  @Autowired private UserRepository users;
  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private NotificationRepository notifications;
  @Autowired private PasswordResetFlowService passwordReset;
  @Autowired private RegistrationService registration;
  @Autowired private NotificationDigestService digest;

  /** Application settings outlive a test (they are cached and stay written); put them back. */
  @AfterEach
  void restoreSettings() {
    settings.update(
        Map.of("general.default_language", "en", "auth.password_reset_enabled", "true"), null);
  }

  private void prefersGerman(UUID userId) {
    storeUserSetting(userId, UserSettingKey.PREFERRED_LANGUAGE.getKey(), "de");
  }

  private String emailOf(UUID userId) {
    return users.findById(userId).map(User::getEmail).orElseThrow();
  }

  /**
   * Waits for the {@code key} mail to {@code to}, asserts it was sent in German, and renders it as
   * MailService would.
   */
  private RenderedMail germanMail(MailTemplateKey key, String to) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000)).sendMailFromTemplate(eq(key), eq(to), vars.capture(), eq("de"));
    return templates.render(key, vars.getValue(), "de");
  }

  private UUID seedReview() {
    Document document = new Document(MEMBER_ID, "Rahmenvertrag");
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    UUID documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    return documentId;
  }

  @Test
  @DisplayName("a review notification reaches a German reviewer in German")
  void reviewNotificationInGerman() throws Exception {
    UUID documentId = seedReview();
    prefersGerman(MEMBER2_ID);
    preferImmediateReviewMail(MEMBER2_ID);

    mockMvc
        .perform(
            post("/api/v1/documents/" + documentId + "/participants")
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isCreated());

    RenderedMail sent = germanMail(MailTemplateKey.REVIEW_PARTICIPANT_ADDED, emailOf(MEMBER2_ID));
    assertThat(sent.subject()).isEqualTo("Du wurdest als Reviewer zu „Rahmenvertrag“ hinzugefügt");
    assertThat(sent.bodyHtml()).contains("Review öffnen").contains("Dies ist eine automatische");
  }

  @Test
  @DisplayName("a decision mail words the decision in the recipient's language")
  void decisionInGerman() throws Exception {
    UUID documentId = seedReview();
    participants.save(ReviewParticipant.forUser(documentId, AUDITOR_ID));
    prefersGerman(MEMBER_ID);
    preferImmediateReviewMail(MEMBER_ID);

    String json =
        mockMvc
            .perform(
                post("/api/v1/documents/" + documentId + "/annotations")
                    .header("Authorization", "Bearer " + token(AUDITOR_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"Cap it\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String annotationId = JsonPath.read(json, "$.id");
    mockMvc
        .perform(
            post("/api/v1/annotations/" + annotationId + "/resolve")
                .header("Authorization", "Bearer " + token(AUDITOR_ID)))
        .andExpect(status().isOk());

    RenderedMail sent = germanMail(MailTemplateKey.REVIEW_ANNOTATION_DECIDED, emailOf(MEMBER_ID));
    assertThat(sent.subject()).isEqualTo("Eine Anmerkung zu „Rahmenvertrag“ wurde erledigt");
  }

  @Test
  @DisplayName("a password reset mail reaches a German user in German, expiry included")
  void passwordResetInGerman() {
    prefersGerman(MEMBER_ID);
    settings.update(Map.of("auth.password_reset_enabled", "true"), null);

    passwordReset.requestReset(emailOf(MEMBER_ID));

    RenderedMail sent = germanMail(MailTemplateKey.PASSWORD_RESET, emailOf(MEMBER_ID));
    assertThat(sent.subject()).startsWith("Setze dein ").endsWith("-Passwort zurück");
    assertThat(sent.bodyPlain())
        .startsWith("Hallo ")
        .containsPattern("läuft in \\d+ (Minuten|Stunden|Tagen) ab");
  }

  @Test
  @DisplayName("an admin-initiated reset reaches a German user in German")
  void adminResetInGerman() {
    prefersGerman(MEMBER_ID);
    User member = users.findById(MEMBER_ID).orElseThrow();

    passwordReset.sendSetupLink(member, MailTemplateKey.ADMIN_PASSWORD_RESET);

    assertThat(germanMail(MailTemplateKey.ADMIN_PASSWORD_RESET, emailOf(MEMBER_ID)).subject())
        .startsWith("Ein Administrator hat dein ");
  }

  @Test
  @DisplayName("a new account without a preference gets the application's default language")
  void registrationUsesTheDefaultLanguage() {
    settings.update(Map.of("general.default_language", "de"), null);

    registration.register("neu", "neu@qnop.test", "Neues-Pass-1234!", "Neu Nutzer");

    RenderedMail sent = germanMail(MailTemplateKey.REGISTRATION_VERIFICATION, "neu@qnop.test");
    assertThat(sent.subject()).startsWith("Bestätige dein ").endsWith("-Konto");
    assertThat(sent.bodyPlain()).startsWith("Hallo Neu Nutzer,");
  }

  @Test
  @DisplayName("the digest reaches a German user with German counts and headings")
  void digestInGerman() {
    UUID documentId = seedReview();
    prefersGerman(MEMBER_ID);
    storeReviewMailCadence(MEMBER_ID, "DAILY");
    // A fixed offset that puts the recipient at noon right now, so the digest is due
    // whatever hour this runs at (the hour arithmetic is DigestScheduleTest's business).
    int offset = 12 - ZonedDateTime.now(ZoneOffset.UTC).getHour();
    storeUserSetting(MEMBER_ID, "timezone", ZoneOffset.ofHours(offset).getId());
    settings.update(Map.of("general.base_url", "https://qnop.example"), null);
    notifications.save(
        Notification.of(MEMBER_ID, NotificationType.COMMENT_ADDED).withDocument(documentId));

    assertThat(digest.digestOnce(false)).startsWith("Sent");

    RenderedMail sent = germanMail(MailTemplateKey.REVIEW_DAILY_DIGEST, emailOf(MEMBER_ID));
    assertThat(sent.subject()).isEqualTo("Review-Zusammenfassung: 1 Neuigkeit in deinen Reviews");
    assertThat(sent.bodyPlain())
        .contains("Rahmenvertrag — 1 Antwort")
        .contains("hat in einer Diskussion geantwortet");
    assertThat(sent.bodyHtml()).contains("Deine Review-Zusammenfassung");
  }
}
