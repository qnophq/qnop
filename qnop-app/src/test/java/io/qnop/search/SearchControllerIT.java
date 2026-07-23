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
package io.qnop.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.Annotation;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end authorization matrix of the federated global search (issue #540, ADR-0047) against a
 * real PostgreSQL: review hits follow the caller's visibility rule with no admin bypass, principal
 * hits follow the enabled-names-only rule (never email), the team {@code viewable} flag tracks
 * membership/adminship, and short queries answer empty. Seeded users/teams come from
 * testdata/db/seed.sql (Alpha: Ada+Mia+Avery, Beta: Mia+Max). Requires Docker.
 */
class SearchControllerIT extends SeededIntegrationTest {

  @Autowired DocumentRepository documents;
  @Autowired ReviewParticipantRepository participants;
  @Autowired TeamRepository teams;
  @Autowired AnnotationRepository annotations;
  @Autowired CommentRepository comments;

  private Document reviewOwnedByMember(String title) {
    return documents.save(new Document(MEMBER_ID, title));
  }

  private void annotationWithComment(UUID documentId, UUID author, String body) {
    Annotation annotation = annotations.save(new Annotation(documentId, author));
    comments.save(new Comment(annotation.getId(), author, body));
  }

  private org.springframework.test.web.servlet.RequestBuilder search(String path, UUID caller) {
    return get(path).header("Authorization", "Bearer " + token(caller));
  }

  @Test
  void reviewHitsFollowTheVisibilityRule() throws Exception {
    Document document = reviewOwnedByMember("Quantum payment terms");

    // The owner finds it ...
    mockMvc
        .perform(search("/api/v1/search?q=payment", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(1))
        .andExpect(jsonPath("$.reviews.items[0].title").value("Quantum payment terms"))
        .andExpect(jsonPath("$.reviews.items[0].workflowState").value("DRAFT"));

    // ... a non-participant does not ...
    mockMvc
        .perform(search("/api/v1/search?q=payment", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0));

    // ... until they join the review.
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    mockMvc
        .perform(search("/api/v1/search?q=payment", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(1));
  }

  @Test
  void adminSearchesTheirOwnReviewsLikeAnyoneElse() throws Exception {
    reviewOwnedByMember("Confidential merger review");

    // No admin bypass — mirroring the reviews overview (ADR-0047).
    mockMvc
        .perform(search("/api/v1/search?q=merger", ADMIN_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0));
  }

  @Test
  void principalsMatchByNameNeverByEmail() throws Exception {
    mockMvc
        .perform(search("/api/v1/search?q=Mia", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users.total").value(1))
        .andExpect(jsonPath("$.users.items[0].displayName").value("Mia Member"));

    // The seeded email finds nothing — email search stays behind /admin/users.
    mockMvc
        .perform(search("/api/v1/search?q=member@qnop.test", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users.total").value(0));
    mockMvc
        .perform(search("/api/v1/search/users?q=member@qnop.test", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void disabledPrincipalsAreInvisible() throws Exception {
    // Dana Disabled is seeded disabled; the disabled team is created here.
    Team disabled = Team.create("Ghost Crew", null);
    disabled.setEnabled(false);
    teams.save(disabled);

    mockMvc
        .perform(search("/api/v1/search?q=Dana", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users.total").value(0));
    mockMvc
        .perform(search("/api/v1/search?q=Ghost", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams.total").value(0));
  }

  @Test
  void teamHitsCarryTheCallersReach() throws Exception {
    // Max is not in Alpha — listed, but locked.
    mockMvc
        .perform(search("/api/v1/search?q=Alpha", MEMBER2_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teams.total").value(1))
        .andExpect(jsonPath("$.teams.items[0].viewable").value(false));

    // Mia is an Alpha member; Ada reaches everything as admin.
    mockMvc
        .perform(search("/api/v1/search?q=Alpha", MEMBER_ID))
        .andExpect(jsonPath("$.teams.items[0].viewable").value(true));
    mockMvc
        .perform(search("/api/v1/search?q=Alpha", ADMIN_ID))
        .andExpect(jsonPath("$.teams.items[0].viewable").value(true));
  }

  @Test
  void discussionTextMatchesWithinVisibleReviewsAndCarriesAnExcerpt() throws Exception {
    Document document = reviewOwnedByMember("Q3 report");
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    annotationWithComment(document.getId(), MEMBER2_ID, "Please rework the liability clause.");

    // The owner finds the review by the comment text, with the excerpt quoted ...
    mockMvc
        .perform(search("/api/v1/search?q=liability", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(1))
        .andExpect(jsonPath("$.reviews.items[0].title").value("Q3 report"))
        .andExpect(
            jsonPath("$.reviews.items[0].excerpt").value("Please rework the liability clause."));

    // ... a non-participant still finds nothing — content search never widens visibility.
    mockMvc
        .perform(search("/api/v1/search?q=liability", AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0));

    // A title match carries no excerpt.
    mockMvc
        .perform(search("/api/v1/search?q=report", MEMBER_ID))
        .andExpect(jsonPath("$.reviews.items[0].excerpt").doesNotExist());
  }

  @Test
  void privateThreadsNeitherMatchNorLeakForForeignReviewers() throws Exception {
    // thread_participation is write-once (updatable = false): set before the INSERT.
    Document unsaved = new Document(MEMBER_ID, "Board minutes");
    unsaved.setThreadParticipation(ThreadParticipation.PRIVATE);
    Document document = documents.save(unsaved);
    participants.save(ReviewParticipant.forUser(document.getId(), MEMBER2_ID));
    participants.save(ReviewParticipant.forUser(document.getId(), AUDITOR_ID));
    annotationWithComment(document.getId(), MEMBER2_ID, "The hidden severance figure.");

    // A fellow participant who cannot open the thread cannot find (or quote) it either.
    mockMvc
        .perform(search("/api/v1/search?q=severance", AUDITOR_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0));

    // The thread's author and the owner search it as they read it.
    mockMvc
        .perform(search("/api/v1/search?q=severance", MEMBER2_ID))
        .andExpect(jsonPath("$.reviews.total").value(1));
    mockMvc
        .perform(search("/api/v1/search?q=severance", MEMBER_ID))
        .andExpect(jsonPath("$.reviews.total").value(1))
        .andExpect(jsonPath("$.reviews.items[0].excerpt").value("The hidden severance figure."));
  }

  @Test
  void shortQueriesAnswerEmptyNeverAnError() throws Exception {
    mockMvc
        .perform(search("/api/v1/search?q=a", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0))
        .andExpect(jsonPath("$.users.total").value(0))
        .andExpect(jsonPath("$.teams.total").value(0));
    mockMvc
        .perform(search("/api/v1/search", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviews.total").value(0));
  }

  @Test
  void pagedReviewsCarryTheEnvelope() throws Exception {
    reviewOwnedByMember("Zeta report one");
    reviewOwnedByMember("Zeta report two");
    reviewOwnedByMember("Zeta report three");

    mockMvc
        .perform(search("/api/v1/search/reviews?q=zeta&page=1&size=2", MEMBER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(2));
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/search?q=alpha")).andExpect(status().isUnauthorized());
  }
}
