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
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.ReviewPseudonym;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.ReviewPseudonymRepository;
import io.qnop.repository.UserRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Per-review anonymity (issue #413): author identity is resolved and, when the review is anonymous,
 * hidden server-side — the API never ships a resolvable identity for a non-self, non-owner author.
 * Owner {@code MEMBER_ID}; reviewers {@code AUDITOR_ID} (the usual author) and {@code MEMBER2_ID}.
 */
class AnnotationAnonymityIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private UserRepository users;
  @Autowired private ReviewPseudonymRepository pseudonyms;

  private static final String PSEUDONYM = "Participant [12]";

  private UUID seedDocument(boolean anonymous) {
    Document document = new Document(MEMBER_ID, "Master services agreement");
    document.setAnonymous(anonymous);
    document = documents.save(document);
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

  private String realName(UUID userId) {
    return users.findById(userId).orElseThrow().getDisplayName();
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  private String createAnnotation(UUID documentId, UUID actor) throws Exception {
    String body = "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"please clarify\"}";
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

  @Test
  void nonAnonymousReviewShipsRealAuthorNames() throws Exception {
    UUID documentId = seedDocument(false);
    createAnnotation(documentId, AUDITOR_ID);

    // A different reviewer sees the author's real name, real id and slug (#486).
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.annotations[0].authorSlug").value("avery-auditor"))
        .andExpect(jsonPath("$.annotations[0].authorDisplayName").value(realName(AUDITOR_ID)));
  }

  @Test
  void anonymousReviewHidesForeignAuthorIdentity() throws Exception {
    UUID documentId = seedDocument(true);
    createAnnotation(documentId, AUDITOR_ID);

    // The foreign reviewer sees a pseudonym and NOT the real id or name. The ordinal covers all
    // non-owner participants (issue #422) in keyed order (issue #914) — so it is 1 or 2 here.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].authorDisplayName").value(matchesPattern(PSEUDONYM)))
        .andExpect(jsonPath("$.annotations[0].authorId").value(not(AUDITOR_ID.toString())))
        .andExpect(jsonPath("$.annotations[0].authorDisplayName").value(not(realName(AUDITOR_ID))))
        // A slug identifies as surely as the id — pseudonymised authors ship none (#486).
        .andExpect(jsonPath("$.annotations[0].authorSlug").doesNotExist());
  }

  @Test
  void anonymousReviewShowsOwnIdentityToSelf() throws Exception {
    UUID documentId = seedDocument(true);
    createAnnotation(documentId, AUDITOR_ID);

    // The author sees their own contribution as themselves — real id, real name.
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.annotations[0].authorDisplayName").value(realName(AUDITOR_ID)));
  }

  @Test
  void anonymousReviewExemptsTheOwner() throws Exception {
    UUID documentId = seedDocument(true);
    // The owner authors an annotation; the owner is structurally public.
    createAnnotation(documentId, MEMBER_ID);

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].authorId").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$.annotations[0].authorDisplayName").value(realName(MEMBER_ID)))
        .andExpect(
            jsonPath("$.annotations[0].authorDisplayName").value(not(startsWith("Participant"))));
  }

  @Test
  void anonymousReviewPseudonymisesCommentAuthors() throws Exception {
    UUID documentId = seedDocument(true);
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // The opening comment (authored by AUDITOR) is pseudonymised for a foreign viewer, under the
    // participant-covering numbering (issue #422).
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments[0].authorDisplayName").value(matchesPattern(PSEUDONYM)))
        .andExpect(jsonPath("$.comments[0].authorId").value(not(AUDITOR_ID.toString())));
  }

  @Test
  void anonymousPseudonymsStayStableWhenContributorsJoin() throws Exception {
    UUID documentId = seedDocument(true);
    String first = createAnnotation(documentId, AUDITOR_ID);
    String before = labelSeenByMember2(documentId, first);

    // A further reviewer joins and contributes: existing labels must not shift (issue #914).
    participants.save(ReviewParticipant.forUser(documentId, ADMIN2_ID));
    String second = createAnnotation(documentId, ADMIN2_ID);

    assertThat(labelSeenByMember2(documentId, first)).isEqualTo(before);
    assertThat(labelSeenByMember2(documentId, second)).isEqualTo("Participant 3");
    int auditorOrdinal = Integer.parseInt(before.substring("Participant ".length()));
    assertThat(pseudonyms.findByDocumentId(documentId))
        .extracting(ReviewPseudonym::getPrincipalId, ReviewPseudonym::getOrdinal)
        .containsExactlyInAnyOrder(
            tuple(AUDITOR_ID, auditorOrdinal),
            tuple(MEMBER2_ID, 3 - auditorOrdinal),
            tuple(ADMIN2_ID, 3));
  }

  @Test
  void readsWriteNoPseudonymsAndShowTheLabelTheFirstWritePersists() throws Exception {
    UUID documentId = seedDocument(true);

    // A read-only roster read assigns nothing but already shows the provisional label.
    String provisional = peerLabelInRosterOfMember2(documentId);
    assertThat(provisional).matches(PSEUDONYM);
    assertThat(pseudonyms.findByDocumentId(documentId)).isEmpty();

    // The first contribution persists exactly that label.
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    assertThat(pseudonyms.findByDocumentId(documentId)).hasSize(2);
    assertThat(peerLabelInRosterOfMember2(documentId)).isEqualTo(provisional);
    assertThat(labelSeenByMember2(documentId, annotationId)).isEqualTo(provisional);
  }

  /** The label MEMBER2 sees for their one peer (AUDITOR) on the roster; own row sorts first. */
  private String peerLabelInRosterOfMember2(UUID documentId) throws Exception {
    String json =
        mockMvc
            .perform(as(get("/api/v1/documents/" + documentId + "/participants"), MEMBER2_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.participants[1].displayName");
  }

  @Test
  void nonAnonymousReviewPersistsNoPseudonyms() throws Exception {
    UUID documentId = seedDocument(false);
    createAnnotation(documentId, AUDITOR_ID);

    assertThat(pseudonyms.findByDocumentId(documentId)).isEmpty();
  }

  private String labelSeenByMember2(UUID documentId, String annotationId) throws Exception {
    String json =
        mockMvc
            .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER2_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> labels =
        JsonPath.read(json, "$.annotations[?(@.id == '" + annotationId + "')].authorDisplayName");
    assertThat(labels).hasSize(1);
    return labels.get(0);
  }

  // ── Roster anonymity (issue #422) ────────────────────────────────────────

  @Test
  void anonymousReviewHidesTheRosterFromForeignReviewers() throws Exception {
    UUID documentId = seedDocument(true);

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/participants"), MEMBER2_ID))
        .andExpect(status().isOk())
        // A peer (AUDITOR) is neither their real id nor their real name — a pseudonym instead.
        .andExpect(
            jsonPath("$.participants[?(@.principalId == '" + AUDITOR_ID + "')]").doesNotExist())
        .andExpect(jsonPath("$.participants[*].displayName", hasItem(matchesPattern(PSEUDONYM))))
        .andExpect(jsonPath("$.participants[*].displayName", not(hasItem(realName(AUDITOR_ID)))))
        // The viewer's own row is sorted first and stays real (they know themselves).
        .andExpect(jsonPath("$.participants[0].principalId").value(MEMBER2_ID.toString()))
        .andExpect(jsonPath("$.participants[0].displayName").value(realName(MEMBER2_ID)));
  }

  @Test
  void anonymousReviewShowsTheRealRosterToTheOwner() throws Exception {
    UUID documentId = seedDocument(true);

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/participants"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.participants[?(@.principalId == '" + AUDITOR_ID + "')].displayName",
                hasItem(realName(AUDITOR_ID))));
  }

  @Test
  void nonAnonymousReviewShowsTheRealRosterToEveryone() throws Exception {
    UUID documentId = seedDocument(false);

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/participants"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.participants[?(@.principalId == '" + AUDITOR_ID + "')].displayName",
                hasItem(realName(AUDITOR_ID))));
  }

  @Test
  void anonymousReviewHidesTheRosterInTheOverview() throws Exception {
    UUID documentId = seedDocument(true);

    mockMvc
        .perform(as(get("/api/v1/documents"), MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.items[?(@.id == '" + documentId + "')].participants[*].displayName",
                not(hasItem(realName(AUDITOR_ID)))));
  }
}
