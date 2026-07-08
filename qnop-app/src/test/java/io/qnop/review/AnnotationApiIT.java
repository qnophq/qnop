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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Annotations, comments & placements API acceptance (issue #247, ADR-0009/0011). Owner {@code
 * MEMBER_ID}; reviewers {@code AUDITOR_ID} (also the annotation author) and {@code MEMBER2_ID};
 * {@code EXTERNAL_ID} is a non-participant and must see 404 everywhere (anti-enumeration).
 */
class AnnotationApiIT extends SeededIntegrationTest {

  private static final String ANCHOR =
      "{\"region\":{\"surfaceIndex\":0,\"box\":{\"x\":0.1,\"y\":0.2,\"width\":0.3,\"height\":0.1}},"
          + "\"textQuote\":{\"quote\":\"the clause\"}}";

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;
  @Autowired private ReviewParticipantRepository participants;
  @Autowired private AuditEventRepository auditEvents;

  /** A DRAFT document owned by MEMBER with AUDITOR + MEMBER2 as reviewers and one version. */
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

  /** Creates an anchor-free, document-scoped annotation (issue #395) on the latest version. */
  private String createDocumentScopedAnnotation(UUID documentId, UUID actor) throws Exception {
    String json =
        mockMvc
            .perform(
                as(post("/api/v1/documents/" + documentId + "/annotations"), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"versionNumber\":1,\"comment\":\"unify terminology across the"
                            + " contract\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.id");
  }

  @Test
  void participantCreatesAnAnnotationPlacedOnTheVersion() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    String body = "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"please clarify\"}";
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.placementStatus").value("PLACED"))
        .andExpect(jsonPath("$.anchor.region.surfaceIndex").value(0))
        .andExpect(jsonPath("$.anchor.textQuote.quote").value("the clause"))
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  void listsAnnotationsWithTheirPlacementForAVersion() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations").param("version", "1"),
                MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1))
        .andExpect(jsonPath("$.annotations[0].placementStatus").value("PLACED"))
        .andExpect(jsonPath("$.annotations[0].anchor.region.box.width").value(0.3));
  }

  @Test
  void createsADocumentScopedAnnotationWithoutAnAnchor() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    // A general remark that pins to no passage (issue #395): the anchor is omitted, so no
    // placement is created — the view carries neither an anchor nor a placement status.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"comment\":\"unify terminology across the contract\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.authorId").value(AUDITOR_ID.toString()))
        .andExpect(jsonPath("$.anchor").doesNotExist())
        .andExpect(jsonPath("$.placementStatus").doesNotExist())
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  void documentScopedAnnotationAppearsOnEveryVersionWithoutAPlacement() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    createDocumentScopedAnnotation(documentId, AUDITOR_ID);

    // A second version is uploaded: a located annotation would re-anchor, but a document-scoped
    // one has no placement to carry forward — it stays valid on every version, never PENDING or
    // ORPHANED (issue #395, ADR-0009).
    versions.save(
        new DocumentVersion(
            documentId, 2, "sha256/bb/cafebabe", "cafebabe", "application/pdf", 2345L, MEMBER_ID));

    for (String version : new String[] {"1", "2"}) {
      mockMvc
          .perform(
              as(
                  get("/api/v1/documents/" + documentId + "/annotations").param("version", version),
                  MEMBER_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.annotations.length()").value(1))
          .andExpect(jsonPath("$.annotations[0].anchor").doesNotExist())
          .andExpect(jsonPath("$.annotations[0].placementStatus").doesNotExist());
    }
  }

  @Test
  void nonParticipantSees404() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), EXTERNAL_ID))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), EXTERNAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"hi\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void theAuthorResolvesWithAClosingNote() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/resolve"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Addressed in the discussion.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        // The note lands as the thread's last comment, authored by the resolver.
        .andExpect(jsonPath("$.commentCount").value(2));

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments[1].body").value("Addressed in the discussion."))
        .andExpect(jsonPath("$.comments[1].authorId").value(AUDITOR_ID.toString()));
  }

  @Test
  void theAuthorResolvesWithoutANote() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  void theOwnerCannotResolveAForeignAnnotation() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // Resolving is the author's call alone (issue #405) — the owner responds
    // in the thread or uploads a new version, but never closes the concern.
    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), MEMBER_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ANNOTATION_ACTION_FORBIDDEN"));

    // The refused decision leaves no audit trail — nobody "resolved" anything (issue #351).
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId))
        .noneMatch(event -> "annotation.resolved".equals(event.getEventType()));
  }

  @Test
  void anotherReviewerCannotResolve() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // MEMBER2 is a participant (so the annotation is visible) but not the author.
    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), MEMBER2_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ANNOTATION_ACTION_FORBIDDEN"));

    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId))
        .noneMatch(event -> "annotation.resolved".equals(event.getEventType()));
  }

  @Test
  void theAuthorsResolveIsAuditedWithTheAuthorAsActor() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    // AUDITOR authors an annotation on a document owned by someone else (MEMBER) and,
    // as the author, may decide (resolve) it — the audit event must attribute the act to
    // the author, not the document owner (issue #351).
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"));

    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(documentId))
        .anySatisfy(
            event -> {
              assertThat(event.getEventType()).isEqualTo("annotation.resolved");
              assertThat(event.getActorId()).isEqualTo(AUDITOR_ID);
              assertThat(event.getDetail()).contains(annotationId);
            });
  }

  @Test
  void aResolvedThreadRefusesNewComments() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());

    // The thread is a closed record (issue #403) — even for the author/owner.
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"too late\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ANNOTATION_ALREADY_RESOLVED"));
  }

  @Test
  void theAuthorReopensTheirResolvedAnnotation() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/reopen"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));

    // The thread accepts comments again (issue #394).
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"welcome back\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void theOwnerCannotReopenAForeignAnnotation() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/reopen"), MEMBER_ID))
        .andExpect(status().isForbidden());
  }

  @Test
  void reopeningAnOpenAnnotationIsConflict() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/reopen"), AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ANNOTATION_NOT_RESOLVED"));
  }

  @Test
  void reopeningOnAFinalizedReviewIsRefused() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());
    Document document = documents.findById(documentId).orElseThrow();
    document.setWorkflowState(WorkflowState.FINALIZED);
    documents.save(document);

    mockMvc
        .perform(as(post("/api/v1/annotations/" + annotationId + "/reopen"), AUDITOR_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));
  }

  @Test
  void aClosedReviewAcceptsNoNewAnnotations() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    Document document = documents.findById(documentId).orElseThrow();
    document.setWorkflowState(WorkflowState.FINALIZED);
    documents.save(document);

    // The workflow guard refuses the raise and rolls the insert back (issue #405).
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"too late\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(0));
  }

  @Test
  void resolvingAnAlreadyResolvedAnnotationIsConflict() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());

    resolve(annotationId, AUDITOR_ID)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ANNOTATION_ALREADY_RESOLVED"));
  }

  @Test
  void addsAndListsComments() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"agreed, please revise\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.body").value("agreed, please revise"));

    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId + "/comments"), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments.length()").value(greaterThanOrEqualTo(2)));
  }

  @Test
  void rejectsAnAnchorWithoutARegion() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":{},\"comment\":\"please clarify\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void createsOnlyOnTheLatestVersion() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    versions.save(
        new DocumentVersion(
            documentId, 2, "sha256/bb/cafebabe", "cafebabe", "application/pdf", 2345L, MEMBER_ID));

    // Older versions are a read-only record (issue #306).
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"too late\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_READ_ONLY"));

    // The latest version keeps accepting annotations.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":2,\"anchor\":" + ANCHOR + ",\"comment\":\"on latest\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void rejectsAnAnnotationWithoutAComment() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    // The first comment is mandatory (issue #301) — the contract itself rejects its absence.
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":" + ANCHOR + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("comment"));
  }

  @Test
  void rejectsAnAnnotationWithABlankComment() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    // Whitespace passes the schema's minLength, so the service guard must catch it (issue #301).
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionNumber\":1,\"anchor\":" + ANCHOR + ",\"comment\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void unknownAnnotationIsNotFound() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/annotations/" + UUID.randomUUID()), MEMBER_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  void carriesTheFirstCommentExcerptEverywhere() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    // The tasks view's card title (issue #393): the first comment rides along on the view.
    String annotationId =
        JsonPath.read(
            mockMvc
                .perform(
                    as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"versionNumber\":1,\"anchor\":"
                                + ANCHOR
                                + ",\"comment\":\"please clarify\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstComment").value("please clarify"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");

    // Later replies never displace it.
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"a later reply\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstComment").value("please clarify"));
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].firstComment").value("please clarify"));
  }

  @Test
  void tracksTheLatestCommentFromOthers() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // The author sees no foreign activity yet; the owner sees the author's opener.
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestCommentFromOthersAt").doesNotExist());
    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].latestCommentFromOthersAt").exists());

    // After the owner replies, the author sees foreign activity too (issue #307).
    mockMvc
        .perform(
            as(post("/api/v1/annotations/" + annotationId + "/comments"), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"the owner weighs in\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestCommentFromOthersAt").exists());
  }

  // --- classification: optional type & priority (issue #392) --------------------------------

  @Test
  void createsWithClassificationAndRoundTripsIt() throws Exception {
    UUID documentId = seedDocumentWithVersion();

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":"
                        + ANCHOR
                        + ",\"comment\":\"conflicts with policy\","
                        + "\"type\":\"CONFLICT\",\"priority\":\"HIGH\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("CONFLICT"))
        .andExpect(jsonPath("$.priority").value("HIGH"));

    mockMvc
        .perform(as(get("/api/v1/documents/" + documentId + "/annotations"), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations[0].type").value("CONFLICT"))
        .andExpect(jsonPath("$.annotations[0].priority").value("HIGH"));
  }

  @Test
  void createsWithoutClassificationAsBefore() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // Uncategorised stays a plain annotation — both fields absent, not defaulted.
    mockMvc
        .perform(as(get("/api/v1/annotations/" + annotationId), MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").doesNotExist())
        .andExpect(jsonPath("$.priority").doesNotExist());
  }

  @Test
  void authorAndOwnerReclassifyWhileOpen() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    mockMvc
        .perform(
            as(patch("/api/v1/annotations/" + annotationId), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"QUESTION\",\"priority\":\"LOW\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("QUESTION"))
        .andExpect(jsonPath("$.priority").value("LOW"));

    // The body replaces the classification wholesale — an absent field clears it.
    mockMvc
        .perform(
            as(patch("/api/v1/annotations/" + annotationId), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RISK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("RISK"))
        .andExpect(jsonPath("$.priority").doesNotExist());
  }

  @Test
  void anotherReviewerCannotReclassify() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);

    // MEMBER2 is a participant (annotation visible) but neither owner nor author.
    mockMvc
        .perform(
            as(patch("/api/v1/annotations/" + annotationId), MEMBER2_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RISK\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void reclassifyingAResolvedAnnotationIsConflict() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    String annotationId = createAnnotation(documentId, AUDITOR_ID);
    resolve(annotationId, AUDITOR_ID).andExpect(status().isOk());

    mockMvc
        .perform(
            as(patch("/api/v1/annotations/" + annotationId), MEMBER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"RISK\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ANNOTATION_ALREADY_RESOLVED"));
  }

  @Test
  void listsFilteredByType() throws Exception {
    UUID documentId = seedDocumentWithVersion();
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), AUDITOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":"
                        + ANCHOR
                        + ",\"comment\":\"a conflict\",\"type\":\"CONFLICT\"}"))
        .andExpect(status().isCreated());
    createAnnotation(documentId, MEMBER2_ID); // uncategorised

    mockMvc
        .perform(
            as(
                get("/api/v1/documents/" + documentId + "/annotations").param("type", "CONFLICT"),
                MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annotations.length()").value(1))
        .andExpect(jsonPath("$.annotations[0].type").value("CONFLICT"));
  }

  private org.springframework.test.web.servlet.ResultActions resolve(
      String annotationId, UUID actor) throws Exception {
    return mockMvc.perform(as(post("/api/v1/annotations/" + annotationId + "/resolve"), actor));
  }
}
