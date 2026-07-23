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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * @mentions end to end (issue #462): a resolved mention mails the mentioned user once, scoped to
 *     the document roster, deduped against the thread mail, disabled in anonymous reviews, and
 *     obeying the mention opt-out. {@link MailService} is mocked, so the assertions read the exact
 *     template key and recipient. Owner = {@code MEMBER_ID}; {@code AUDITOR_ID} is a seeded
 *     participant.
 */
class MentionNotificationIT extends SeededIntegrationTest {

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

  /** A canonical mention token; the free-text label is irrelevant, resolution is by the id. */
  private static String mention(UUID userId) {
    return "[@somebody](mention:" + userId + ")";
  }

  private String createAnnotation(UUID author, String comment) throws Exception {
    String json =
        mockMvc
            .perform(
                post("/api/v1/documents/" + documentId + "/annotations")
                    .header("Authorization", "Bearer " + token(author))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"anchor\":"
                            + ANCHOR
                            + ",\"comment\":\""
                            + comment
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private void reply(UUID author, String annotationId, String body) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/annotations/" + annotationId + "/comments")
                .header("Authorization", "Bearer " + token(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("a mention in the opening comment mails the mentioned roster member")
  void mentionInOpeningCommentMailsTheMentionedUser() throws Exception {
    seedDocument(false);
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));

    createAnnotation(AUDITOR_ID, "Please weigh in " + mention(MEMBER2_ID));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_MENTION), eq(emailOf(MEMBER2_ID)), vars.capture(), isNull());
    assertThat((String) vars.getValue().get("actionUrl")).contains("annotation=", "comment=");
  }

  @Test
  @DisplayName("a mentioned thread follower gets one mail — the mention, not the reply")
  void mentionDedupesAgainstTheThreadMail() throws Exception {
    seedDocument(false);
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));
    String annotationId = createAnnotation(AUDITOR_ID, "Opening remark");
    reply(MEMBER2_ID, annotationId, "I am following this thread."); // MEMBER2 joins the thread

    reply(AUDITOR_ID, annotationId, "One more point " + mention(MEMBER2_ID));

    // MEMBER2 is both a thread follower and mentioned: exactly the mention mail, never the reply
    // mail.
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_MENTION), eq(emailOf(MEMBER2_ID)), anyMap(), isNull());
    verify(mail, after(300).never())
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_COMMENT_ADDED), eq(emailOf(MEMBER2_ID)), anyMap(), any());
  }

  @Test
  @DisplayName("a mention of a non-roster user resolves to nothing — no mail")
  void mentionOfNonParticipantIsIgnored() throws Exception {
    seedDocument(false); // roster: MEMBER_ID (owner), AUDITOR_ID; MEMBER2_ID is NOT a participant

    createAnnotation(AUDITOR_ID, "Ping the outsider " + mention(MEMBER2_ID));

    // The owner's annotation mail proves dispatch ran; the off-roster mention produced no mail.
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_CREATED),
            eq(emailOf(MEMBER_ID)),
            anyMap(),
            isNull());
    verify(mail, after(300).never())
        .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_MENTION), any(), anyMap(), any());
  }

  @Test
  @DisplayName("an anonymous review resolves no mentions — no mention mail")
  void anonymousReviewSendsNoMentionMail() throws Exception {
    seedDocument(true);
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));

    createAnnotation(AUDITOR_ID, "Ping " + mention(MEMBER2_ID));

    // The owner's annotation mail proves dispatch ran; no mention was resolved or mailed.
    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_CREATED),
            eq(emailOf(MEMBER_ID)),
            anyMap(),
            isNull());
    verify(mail, after(300).never())
        .sendMailFromTemplate(eq(MailTemplateKey.REVIEW_MENTION), any(), anyMap(), any());
  }

  @Test
  @DisplayName("a mention respects the mention opt-out")
  void mentionRespectsTheOptOut() throws Exception {
    seedDocument(false);
    participants.save(ReviewParticipant.forUser(documentId, MEMBER2_ID));
    userSettings.save(new UserSetting(MEMBER2_ID, UserSettingKey.EMAIL_MENTIONS.getKey(), "false"));

    createAnnotation(AUDITOR_ID, "Ping " + mention(MEMBER2_ID));

    verify(mail, timeout(5000))
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_ANNOTATION_CREATED),
            eq(emailOf(MEMBER_ID)),
            anyMap(),
            isNull());
    verify(mail, after(300).never())
        .sendMailFromTemplate(
            eq(MailTemplateKey.REVIEW_MENTION), eq(emailOf(MEMBER2_ID)), anyMap(), any());
  }
}
