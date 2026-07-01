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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Annotation;
import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AnnotationStatus;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ParticipantRole;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the document-review schema (issue #244, ADR-0011/0009/0032) against a real PostgreSQL
 * (ADR-0020): UUIDv7 identity, jsonb round-tripping, the repository finders, the {@code ON DELETE}
 * policy (aggregate CASCADE vs. user-reference RESTRICT), the participant user-XOR-team {@code
 * CHECK}, the closed status enums, the unique constraints, and — deliberately — that {@code
 * workflow_state} is <em>open</em> so an enterprise-extended state machine needs no schema change.
 * Each test runs in a rolled-back transaction. Extends {@link AbstractIntegrationTest}
 * (Testcontainers). Requires Docker.
 */
@Transactional
class DocumentReviewSchemaIT extends AbstractIntegrationTest {

  @Autowired DocumentRepository documents;
  @Autowired DocumentVersionRepository versions;
  @Autowired ReviewParticipantRepository participants;
  @Autowired AnnotationRepository annotations;
  @Autowired CommentRepository comments;
  @Autowired AnnotationPlacementRepository placements;
  @Autowired AuditEventRepository auditEvents;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager entityManager;

  // --- happy path: the full graph -----------------------------------------

  @Test
  @DisplayName("persists the full review graph with generated UUIDv7 ids and jsonb anchors")
  void persistsFullReviewGraph() {
    User owner = newUser("Owner");
    User author = newUser("Author");
    Team team = newTeam();

    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Spec v1"));
    assertThat(doc.getId().version()).isEqualTo(7); // UUIDv7
    assertThat(doc.getWorkflowState()).isEqualTo(WorkflowState.DRAFT.name());

    DocumentVersion v1 = versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId()));
    versions.saveAndFlush(newVersion(doc.getId(), 2, owner.getId()));

    participants.saveAndFlush(
        ReviewParticipant.forUser(doc.getId(), author.getId(), ParticipantRole.REVIEWER));
    participants.saveAndFlush(
        ReviewParticipant.forTeam(doc.getId(), team.getId(), ParticipantRole.REVIEWER));

    Annotation ann = annotations.saveAndFlush(new Annotation(doc.getId(), author.getId()));
    comments.saveAndFlush(new Comment(ann.getId(), author.getId(), "First remark"));
    comments.saveAndFlush(new Comment(ann.getId(), owner.getId(), "Owner reply"));
    placements.saveAndFlush(
        new AnnotationPlacement(ann.getId(), v1.getId(), "{\"page\":1,\"quote\":\"hello\"}"));
    auditEvents.saveAndFlush(new AuditEvent(doc.getId(), "DOCUMENT_CREATED", owner.getId(), null));
    auditEvents.saveAndFlush(
        new AuditEvent(doc.getId(), "VERSION_UPLOADED", owner.getId(), "{\"n\":2}"));

    entityManager.clear();

    assertThat(documents.findByOwnerId(owner.getId()))
        .extracting(Document::getId)
        .contains(doc.getId());
    assertThat(versions.findByDocumentIdOrderByVersionNumberAsc(doc.getId()))
        .extracting(DocumentVersion::getVersionNumber)
        .containsExactly(1, 2);
    assertThat(
            versions
                .findTopByDocumentIdOrderByVersionNumberDesc(doc.getId())
                .orElseThrow()
                .getVersionNumber())
        .isEqualTo(2);
    assertThat(participants.findByDocumentId(doc.getId())).hasSize(2);
    assertThat(participants.findByUserId(author.getId())).hasSize(1);
    assertThat(participants.findByTeamId(team.getId())).hasSize(1);
    assertThat(annotations.countByDocumentIdAndStatus(doc.getId(), AnnotationStatus.OPEN))
        .isEqualTo(1);
    assertThat(comments.findByAnnotationIdOrderByCreatedAtAsc(ann.getId()))
        .extracting(Comment::getBody)
        .containsExactlyInAnyOrder("First remark", "Owner reply");
    AnnotationPlacement reloaded =
        placements.findByAnnotationIdAndDocumentVersionId(ann.getId(), v1.getId()).orElseThrow();
    assertThat(reloaded.getAnchor()).contains("\"page\"").contains("hello"); // jsonb round-trips
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(doc.getId()))
        .extracting(AuditEvent::getEventType)
        .containsExactlyInAnyOrder("DOCUMENT_CREATED", "VERSION_UPLOADED");
  }

  @Test
  @DisplayName("a version's rendered_document jsonb is attached after insert and round-trips")
  void attachesRenderedDocumentJsonb() {
    User owner = newUser("Owner");
    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Doc"));
    DocumentVersion v = versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId()));
    assertThat(v.getRenderedDocument()).isNull();

    v.attachRenderedDocument("{\"pages\":[{\"index\":0,\"text\":\"body\"}]}");
    versions.saveAndFlush(v);
    entityManager.clear();

    assertThat(versions.findById(v.getId()).orElseThrow().getRenderedDocument())
        .contains("\"pages\"")
        .contains("body");
  }

  // --- delete policy -------------------------------------------------------

  @Test
  @DisplayName("deleting a document cascades its whole aggregate")
  void cascadesAggregateOnDocumentDelete() {
    User owner = newUser("Owner");
    Team team = newTeam();
    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Doc"));
    DocumentVersion v = versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId()));
    participants.saveAndFlush(
        ReviewParticipant.forTeam(doc.getId(), team.getId(), ParticipantRole.REVIEWER));
    Annotation ann = annotations.saveAndFlush(new Annotation(doc.getId(), owner.getId()));
    comments.saveAndFlush(new Comment(ann.getId(), owner.getId(), "c"));
    placements.saveAndFlush(new AnnotationPlacement(ann.getId(), v.getId(), "{\"p\":1}"));
    auditEvents.saveAndFlush(new AuditEvent(doc.getId(), "X", owner.getId(), null));

    documents.deleteById(doc.getId());
    entityManager.flush();
    entityManager.clear();

    assertThat(versions.findByDocumentIdOrderByVersionNumberAsc(doc.getId())).isEmpty();
    assertThat(participants.findByDocumentId(doc.getId())).isEmpty();
    assertThat(annotations.findByDocumentId(doc.getId())).isEmpty();
    assertThat(comments.findByAnnotationIdOrderByCreatedAtAsc(ann.getId())).isEmpty();
    assertThat(placements.findByAnnotationId(ann.getId())).isEmpty();
    assertThat(auditEvents.findByDocumentIdOrderByCreatedAtDesc(doc.getId())).isEmpty();
    // The team and user survive — the aggregate cascade does not reach principals.
    assertThat(teams.findById(team.getId())).isPresent();
    assertThat(users.findById(owner.getId())).isPresent();
  }

  @Test
  @DisplayName("a user who owns a document cannot be hard-deleted (RESTRICT)")
  void restrictsDeletingOwnerWithDocuments() {
    User owner = newUser("Owner");
    documents.saveAndFlush(new Document(owner.getId(), "Doc"));

    assertThatThrownBy(
            () -> {
              users.deleteById(owner.getId());
              users.flush(); // repository flush → Spring exception translation applies
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- constraints ---------------------------------------------------------

  @Test
  @DisplayName("a participant with both a user and a team violates the XOR check")
  void rejectsParticipantWithBothPrincipals() {
    Document doc = docWithOwner();
    UUID userId = newUser("U").getId();
    UUID teamId = newTeam().getId();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO review_participant (id, document_id, user_id, team_id, role,"
                        + " created_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'REVIEWER', now())",
                    UUID.randomUUID().toString(),
                    doc.getId().toString(),
                    userId.toString(),
                    teamId.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("a participant with neither a user nor a team violates the XOR check")
  void rejectsParticipantWithNoPrincipal() {
    Document doc = docWithOwner();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO review_participant (id, document_id, user_id, team_id, role,"
                        + " created_at) VALUES (?::uuid, ?::uuid, NULL, NULL, 'REVIEWER', now())",
                    UUID.randomUUID().toString(),
                    doc.getId().toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an annotation status outside the closed set is rejected")
  void rejectsUnknownAnnotationStatus() {
    Document doc = docWithOwner();
    UUID authorId = newUser("A").getId();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO annotation (id, document_id, author_id, status, created_at,"
                        + " updated_at, version) VALUES (?::uuid, ?::uuid, ?::uuid, 'BOGUS', now(),"
                        + " now(), 0)",
                    UUID.randomUUID().toString(),
                    doc.getId().toString(),
                    authorId.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("workflow_state has no closed check — an enterprise state persists")
  void workflowStateColumnIsOpenForEnterpriseStates() {
    User owner = newUser("Owner");
    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Doc"));

    // An enterprise-only state (e.g. a signing gate, ADR-0035) must persist without a schema
    // change.
    int updated =
        jdbc.update(
            "UPDATE document SET workflow_state = 'PENDING_SIGNATURE' WHERE id = ?::uuid",
            doc.getId().toString());
    assertThat(updated).isEqualTo(1);

    String stored =
        jdbc.queryForObject(
            "SELECT workflow_state FROM document WHERE id = ?::uuid",
            String.class,
            doc.getId().toString());
    assertThat(stored).isEqualTo("PENDING_SIGNATURE");
    // The Community enum recognises its own states but tolerates the unknown one.
    assertThat(WorkflowState.fromString(stored)).isEmpty();
    assertThat(WorkflowState.fromString("DRAFT")).contains(WorkflowState.DRAFT);
  }

  @Test
  @DisplayName("two versions cannot share a version number within a document")
  void enforcesVersionNumberUniqueness() {
    User owner = newUser("Owner");
    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Doc"));
    versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId()));

    assertThatThrownBy(() -> versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an annotation has at most one placement per version")
  void enforcesUniquePlacementPerVersion() {
    User owner = newUser("Owner");
    Document doc = documents.saveAndFlush(new Document(owner.getId(), "Doc"));
    DocumentVersion v = versions.saveAndFlush(newVersion(doc.getId(), 1, owner.getId()));
    Annotation ann = annotations.saveAndFlush(new Annotation(doc.getId(), owner.getId()));
    placements.saveAndFlush(new AnnotationPlacement(ann.getId(), v.getId(), "{\"p\":1}"));

    assertThatThrownBy(
            () ->
                placements.saveAndFlush(
                    new AnnotationPlacement(ann.getId(), v.getId(), "{\"p\":2}")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- helpers -------------------------------------------------------------

  private User newUser(String displayName) {
    return users.saveAndFlush(
        User.external(displayName, "u-" + UUID.randomUUID() + "@example.com"));
  }

  private Team newTeam() {
    return teams.saveAndFlush(Team.create("t-" + UUID.randomUUID(), "desc"));
  }

  private Document docWithOwner() {
    return documents.saveAndFlush(new Document(newUser("Owner").getId(), "Doc"));
  }

  private DocumentVersion newVersion(UUID documentId, int number, UUID createdBy) {
    return new DocumentVersion(
        documentId,
        number,
        "storage/key-" + number + "-" + UUID.randomUUID(),
        "hash-" + number,
        "application/pdf",
        1234L,
        createdBy);
  }
}
