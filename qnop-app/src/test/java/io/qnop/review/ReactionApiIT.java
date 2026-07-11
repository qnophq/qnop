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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Slack-style emoji reactions on annotations and comments over the wire (issue #410): the
 * idempotent PUT/DELETE toggle pair, the grouped chips batched onto the views, the RESOLVED-stays-
 * reactable rule versus the closed-review refusal, participants-only visibility, emoji validation
 * and — deliberately — a multi-code-point emoji in the URL path.
 */
class ReactionApiIT extends SeededIntegrationTest {

  private static final String THUMBS = "👍";
  private static final String PARTY = "🎉";

  /** Skin tone + ZWJ pressure on the path-variable encoding. */
  private static final String FAMILY = "👨‍👩‍👧‍👦";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;

  private UUID seedDocumentWithVersion() {
    Document document = documents.save(new Document(MEMBER_ID, "Master services agreement"));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    versions.save(
        new DocumentVersion(
            document.getId(),
            1,
            "sha256/aa/deadbeef",
            "deadbeef",
            "application/pdf",
            1234L,
            MEMBER_ID));
    return document.getId();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private String createAnnotation(UUID documentId, UUID actor) throws Exception {
    String body = "{\"versionNumber\":1,\"comment\":\"please clarify\"}";
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  private String addComment(String annotationId, UUID actor, String body) throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/annotations/" + annotationId + "/comments"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"body\":\"" + body + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  @Test
  @DisplayName("react/unreact on an annotation round-trips idempotently, groups on the view")
  void annotationReactionRoundTrip() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);

    // PUT twice — the second is a no-op, not a duplicate and not an error.
    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), MEMBER_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), MEMBER_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reactions", hasSize(1)))
        .andExpect(jsonPath("$.reactions[0].emoji").value(THUMBS))
        .andExpect(jsonPath("$.reactions[0].count").value(1))
        .andExpect(jsonPath("$.reactions[0].reactedByMe").value(true))
        .andExpect(jsonPath("$.reactions[0].reactors", hasSize(1)));

    // DELETE twice — same idempotency on the way out.
    mockMvc
        .perform(
            as(
                delete("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS),
                MEMBER_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(
                delete("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS),
                MEMBER_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reactions", hasSize(0)));
  }

  @Test
  @DisplayName("same emojis group with counts and reactor names; the list endpoint batches them")
  void groupsAcrossUsers() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);

    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), MEMBER_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), AUDITOR_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, PARTY), MEMBER_ID))
        .andExpect(status().isNoContent());

    // The viewer is AUDITOR: 👍 carries them, 🎉 does not.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].reactions", hasSize(2)))
        .andExpect(jsonPath("$.annotations[0].reactions[0].emoji").value(THUMBS))
        .andExpect(jsonPath("$.annotations[0].reactions[0].count").value(2))
        .andExpect(jsonPath("$.annotations[0].reactions[0].reactedByMe").value(true))
        .andExpect(jsonPath("$.annotations[0].reactions[0].reactors", hasSize(2)))
        .andExpect(jsonPath("$.annotations[0].reactions[1].emoji").value(PARTY))
        .andExpect(jsonPath("$.annotations[0].reactions[1].count").value(1))
        .andExpect(jsonPath("$.annotations[0].reactions[1].reactedByMe").value(false));
  }

  @Test
  @DisplayName("comment reactions arrive batched on the thread, ZWJ emoji survive the path")
  void commentReactions() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);
    String commentId = addComment(annotationId, AUDITOR_ID, "second opinion");

    mockMvc
        .perform(as(put("/api/v1/comments/{id}/reactions/{emoji}", commentId, FAMILY), MEMBER2_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments[0].reactions", hasSize(0)))
        .andExpect(jsonPath("$.comments[1].reactions", hasSize(1)))
        .andExpect(jsonPath("$.comments[1].reactions[0].emoji").value(FAMILY))
        .andExpect(jsonPath("$.comments[1].reactions[0].reactedByMe").value(true));

    mockMvc
        .perform(
            as(delete("/api/v1/comments/{id}/reactions/{emoji}", commentId, FAMILY), MEMBER2_ID))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments[1].reactions", hasSize(0)));
  }

  @Test
  @DisplayName(
      "a RESOLVED annotation stays reactable — a reaction is not a discussion contribution")
  void resolvedStaysReactable() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/resolve"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), AUDITOR_ID))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("a FINALIZED review refuses reactions with REVIEW_CLOSED")
  void closedReviewRefuses() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);
    Document document = documents.findById(documentId).orElseThrow();
    document.setWorkflowState(WorkflowState.FINALIZED);
    documents.save(document);

    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS), MEMBER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));
    mockMvc
        .perform(
            as(
                delete("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS),
                MEMBER_ID))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("a non-participant sees the same 404 as for the document (anti-enumeration)")
  void nonParticipantGets404() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);

    mockMvc
        .perform(
            as(
                put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, THUMBS),
                EXTERNAL_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("prose in the emoji segment is rejected as 400")
  void rejectsNonEmoji() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, MEMBER_ID);

    mockMvc
        .perform(
            as(put("/api/v1/annotations/{id}/reactions/{emoji}", annotationId, "abc"), MEMBER_ID))
        .andExpect(status().isBadRequest());
  }
}
