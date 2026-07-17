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
package io.qnop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.User;
import io.qnop.entity.UserSetting;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserSettingRepository;
import io.qnop.service.UserSettingKey;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Review events end to end (issue #316): REST actions land as template mails to the right people —
 * asynchronously after commit (hence Mockito's {@code timeout}), never to the actor, never to
 * opted-out users, and derived workflow flips stay silent. {@link MailService} is mocked, so no
 * SMTP is involved and the assertions read the exact template key, recipient and variables.
 */
class ReviewNotificationIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @MockitoBean private MailService mail;

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private UserRepository users;
  @Autowired private UserSettingRepository userSettings;

  private UUID documentId;

  private void seedDocument(boolean anonymous) {
    Document document = new Document(MEMBER_ID, "Master services agreement");
    document.setAnonymous(anonymous);
    document.setWorkflowState(WorkflowState.IN_REVIEW);
    documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId, 1, "sha256/aa/deadbeef", "deadbeef", "application/pdf", 1234L, MEMBER_ID));
    participants.save(ReviewParticipant.forUser(documentId, AUDITOR_ID));
  }

  private String emailOf(UUID userId) {
    return users.findById(userId).map(User::getEmail).orElseThrow();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private String createAnnotation(UUID author) throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"anchor\":"
                            + ANCHOR
                            + ",\"comment\":\"**Check** the cap\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  @Test
  @DisplayName("adding a reviewer mails the new reviewer, and only them")
  void participantAddedMailsTheReviewer() throws Exception {
    seedDocument(false);

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isCreated());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_PARTICIPANT_ADDED),
            eq(emailOf(MEMBER2_ID)),
            vars.capture(),
            isNull());
    assertThat(vars.getValue())
        .containsEntry("documentTitle", "Master services agreement")
        .containsKey("actionUrl");
    verify(mail, after(300).never())
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_PARTICIPANT_ADDED), eq(emailOf(AUDITOR_ID)), anyMap(), any());
  }

  @Test
  @DisplayName("a new annotation mails the owner; the derived workflow flip stays silent")
  void annotationCreatedMailsTheOwner() throws Exception {
    seedDocument(false);

    createAnnotation(AUDITOR_ID);

    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_CREATED),
            eq(emailOf(MEMBER_ID)),
            anyMap(),
            isNull());
    // The raise derived IN_REVIEW -> CHANGES_REQUESTED — announced by the annotation
    // mail itself, not a second workflow mail.
    verify(mail, after(300).never())
        .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_WORKFLOW_CHANGED), any(), anyMap(), any());
  }

  @Test
  @DisplayName("a reply mails the thread (author), never the actor")
  void commentAddedMailsTheThread() throws Exception {
    seedDocument(false);
    String annotationId = createAnnotation(AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Capping at 12 months works.\"}"))
        .andExpect(status().isCreated());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_COMMENT_ADDED),
            eq(emailOf(AUDITOR_ID)),
            vars.capture(),
            isNull());
    assertThat(vars.getValue()).containsEntry("commentExcerpt", "Capping at 12 months works.");
    assertThat((String) vars.getValue().get("actionUrl")).contains("annotation=", "comment=");
  }

  @Test
  @DisplayName("resolving mails the owner with the decision")
  void resolveMailsTheOwner() throws Exception {
    seedDocument(false);
    String annotationId = createAnnotation(AUDITOR_ID);

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), AUDITOR_ID))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_DECIDED),
            eq(emailOf(MEMBER_ID)),
            vars.capture(),
            isNull());
    assertThat(vars.getValue()).containsEntry("decision", "resolved");
  }

  @Test
  @DisplayName("a manual transition mails the participants — unless they opted out")
  void manualTransitionMailsParticipantsRespectingOptOut() throws Exception {
    seedDocument(false);
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));
    userSettings.save(
        new UserSetting(MEMBER2_ID, UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getKey(), "false"));

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/workflow"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetState\":\"CANCELLED\"}"))
        .andExpect(status().isOk());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_WORKFLOW_CHANGED),
            eq(emailOf(AUDITOR_ID)),
            vars.capture(),
            isNull());
    assertThat(vars.getValue())
        .containsEntry("oldState", "In review")
        .containsEntry("newState", "Cancelled");
    verify(mail, after(300).never())
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_WORKFLOW_CHANGED), eq(emailOf(MEMBER2_ID)), anyMap(), any());
  }

  @Test
  @DisplayName("a new version mails every participant with the version deep link")
  void versionUploadedMailsParticipants() throws Exception {
    seedDocument(false);

    try (PDDocument pdf = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      pdf.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.beginText();
        content.newLineAtOffset(72, 700);
        content.showText("the clause, revised");
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      pdf.save(out);
      mockMvc
          .perform(
              multipart("/api/v1/documents/" + documentId + "/versions")
                  .file(
                      new MockMultipartFile(
                          "file", "doc.pdf", "application/pdf", out.toByteArray()))
                  .header("Authorization", "Bearer " + token(MEMBER_ID)))
          .andExpect(status().isCreated());
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_VERSION_UPLOADED),
            eq(emailOf(AUDITOR_ID)),
            vars.capture(),
            isNull());
    assertThat(vars.getValue()).containsEntry("versionNumber", "2");
    assertThat((String) vars.getValue().get("actionUrl")).endsWith("?version=2");
  }

  @Test
  @DisplayName("an anonymous review never mails a foreign author's real name")
  void anonymousReviewKeepsAuthorsPseudonymous() throws Exception {
    seedDocument(true);

    createAnnotation(AUDITOR_ID);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_CREATED),
            eq(emailOf(MEMBER_ID)),
            vars.capture(),
            isNull());
    String actorName = (String) vars.getValue().get("actorName");
    String realName = users.findById(AUDITOR_ID).map(User::getDisplayName).orElseThrow();
    assertThat(actorName).isNotEqualTo(realName).startsWith("Participant");
  }
}
