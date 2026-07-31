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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ThreadParticipation;
import io.qnop.entity.WorkflowState;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Admins list and moderate every review (issue #563).
 *
 * <p>The listing is the part with teeth: it is the one query in the product that deliberately drops
 * the participation predicate, so what it returns — and to whom it refuses to return anything — is
 * asserted per role rather than assumed.
 */
class AdminReviewModerationIT extends SeededIntegrationTest {

  @Autowired private DocumentRepository documents;
  @Autowired private DocumentVersionRepository versions;

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, UUID user) {
    return builder.header("Authorization", "Bearer " + token(user));
  }

  /** A review owned by MEMBER that nobody else is part of. */
  private UUID foreignReview(String title) {
    return foreignReview(title, ThreadParticipation.OPEN);
  }

  private UUID foreignReview(String title, WorkflowState state) {
    return foreignReview(title, ThreadParticipation.OPEN, state);
  }

  private UUID foreignReview(String title, ThreadParticipation threads) {
    return foreignReview(title, threads, WorkflowState.IN_REVIEW);
  }

  private UUID foreignReview(String title, ThreadParticipation threads, WorkflowState state) {
    Document document = new Document(MEMBER_ID, title);
    document.setWorkflowState(state);
    document.setThreadParticipation(threads);
    UUID documentId = documents.save(document).getId();
    versions.save(
        new DocumentVersion(
            documentId,
            1,
            "sha256/aa/" + title.hashCode(),
            "hash",
            "application/pdf",
            1L,
            MEMBER_ID));
    return documentId;
  }

  private String list(UUID actor, String participation, String... params) throws Exception {
    var request =
        get("/api/v1/documents").param("participation", participation).param("size", "100");
    for (int index = 0; index + 1 < params.length; index += 2) {
      request = request.param(params[index], params[index + 1]);
    }
    return mockMvc
        .perform(as(request, actor))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static List<String> titles(String json) {
    return JsonPath.read(json, "$.items[*].title");
  }

  /** One field of the row with the given title, as text. */
  private static String field(String json, String title, String field) {
    return JsonPath.read(json, "$.items[?(@.title == '" + title + "')]." + field).toString();
  }

  @Test
  @DisplayName("the default listing stays the caller's own, for an admin as much as anyone")
  void defaultListingIsUnchangedForAdmins() throws Exception {
    foreignReview("Not the admins review");

    // The point of the opt-in: an admin's own work must not be drowned out by
    // every review in the workspace on every visit.
    assertThat(titles(list(ADMIN_ID, "mine"))).doesNotContain("Not the admins review");
    assertThat(titles(list(ADMIN_ID, "all"))).contains("Not the admins review");
  }

  @Test
  @DisplayName("only an admin may ask for every review; everyone else is refused")
  void moderationListingIsAdminOnly() throws Exception {
    foreignReview("Someone elses review");

    for (UUID actor : List.of(MEMBER2_ID, AUDITOR_ID, EXTERNAL_ID)) {
      // 403 rather than 404: the listing is not a document whose existence could be
      // probed, so hiding it would only obscure why the call failed. The AUDITOR is
      // in this list deliberately — their organisation-wide view is the audit trail
      // (ADR-0042), not the review list.
      mockMvc
          .perform(as(get("/api/v1/documents").param("participation", "all"), actor))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    assertThat(titles(list(MEMBER2_ID, "mine"))).doesNotContain("Someone elses review");
  }

  @Test
  @DisplayName("reviews the admin is not part of are marked as such")
  void foreignReviewsAreMarked() throws Exception {
    foreignReview("Moderated from outside");
    Document own = new Document(ADMIN_ID, "The admins own");
    own.setWorkflowState(WorkflowState.IN_REVIEW);
    documents.save(own);

    String json = list(ADMIN_ID, "all");

    assertThat(field(json, "Moderated from outside", "participating")).contains("false");
    assertThat(field(json, "The admins own", "participating")).contains("true");
  }

  @Test
  @DisplayName("the moderation listing counts private threads the admin can already read")
  void countsAreNotUnderReported() throws Exception {
    UUID documentId = foreignReview("Private threads", ThreadParticipation.PRIVATE);
    annotate(documentId, MEMBER_ID);

    // The admin reads PRIVATE threads (AnnotationService.canSeeThread), so a row
    // showing "0 open" would be a wrong number rather than privacy.
    assertThat(field(list(ADMIN_ID, "all"), "Private threads", "openAnnotationCount"))
        .contains("1");
    // A stranger's own listing is unaffected — they never see the review at all.
    assertThat(titles(list(MEMBER2_ID, "mine"))).doesNotContain("Private threads");
  }

  @Test
  @DisplayName("an admin edits the due date of a review they do not own")
  void adminMayEditTheDueDate() throws Exception {
    UUID documentId = foreignReview("Deadline moved by an admin");
    String body = "{\"dueAt\":\"" + Instant.now().plus(14, ChronoUnit.DAYS).toString() + "\"}";

    mockMvc
        .perform(
            as(patch("/api/v1/documents/" + documentId), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // A non-participant without the admin role still cannot see it at all.
    mockMvc
        .perform(
            as(patch("/api/v1/documents/" + documentId), MEMBER2_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an admin adds a reviewer to a review they do not own")
  void adminMayManageParticipants() throws Exception {
    UUID documentId = foreignReview("Reviewer added by an admin");

    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/participants"), ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + MEMBER2_ID + "\"}"))
        .andExpect(status().isCreated());

    // And the reviewer now sees it in their own listing, which is the point of
    // being able to do it at all.
    assertThat(titles(list(MEMBER2_ID, "mine"))).contains("Reviewer added by an admin");
  }

  @Test
  @DisplayName("the facets filter the workspace on the server, not the page on screen")
  void facetsAreServerSide() throws Exception {
    foreignReview("Closed foreign review", WorkflowState.FINALIZED);
    foreignReview("Open foreign review");
    Document own = new Document(ADMIN_ID, "The admins own open one");
    own.setWorkflowState(WorkflowState.IN_REVIEW);
    documents.save(own);

    // The workflow slice is a predicate, orthogonal to the retention one.
    String open = list(ADMIN_ID, "all", "lifecycle", "open");
    assertThat(titles(open)).contains("Open foreign review", "The admins own open one");
    assertThat(titles(open)).doesNotContain("Closed foreign review");

    // And so is the caller's part in it — the facet that only this listing has.
    String observed = list(ADMIN_ID, "all", "role", "observer");
    assertThat(titles(observed)).contains("Open foreign review");
    assertThat(titles(observed)).doesNotContain("The admins own open one");

    String owned = list(ADMIN_ID, "all", "role", "owner");
    assertThat(titles(owned)).containsOnly("The admins own open one");
  }

  @Test
  @DisplayName("the chip counts describe the workspace, not the page")
  void facetCountsSpanTheWorkspace() throws Exception {
    for (int index = 0; index < 3; index++) {
      foreignReview("Foreign review " + index);
    }
    Document own = new Document(ADMIN_ID, "Owned by the admin");
    own.setWorkflowState(WorkflowState.IN_REVIEW);
    documents.save(own);

    // One row per page, so a count taken from the page would read 1 for
    // everything — which is exactly the lie the server-side counts prevent.
    String json =
        mockMvc
            .perform(
                as(
                    get("/api/v1/documents")
                        .param("participation", "all")
                        .param("size", "1")
                        .param("q", "review "),
                    ADMIN_ID))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat((Integer) JsonPath.read(json, "$.items.length()")).isEqualTo(1);
    assertThat(((Number) JsonPath.read(json, "$.facets.roleObserver")).intValue()).isEqualTo(3);
    assertThat(((Number) JsonPath.read(json, "$.facets.roleOwner")).intValue()).isZero();
    assertThat(json).doesNotContain("Owned by the admin"); // the search excluded it
  }

  @Test
  @DisplayName("the advanced filters narrow the workspace, and the owner facet spans it")
  void advancedFiltersAreServerSide() throws Exception {
    foreignReview("Cancelled elsewhere", WorkflowState.CANCELLED);
    foreignReview("Still in review");

    // One specific workflow state, not just open/closed.
    String cancelled = list(ADMIN_ID, "all", "workflowState", "CANCELLED", "scope", "all");
    assertThat(titles(cancelled)).contains("Cancelled elsewhere");
    assertThat(titles(cancelled)).doesNotContain("Still in review");

    // Owned by somebody: the facet offers owners from the whole workspace, so it
    // can name people whose reviews are not on the current page.
    String json = list(ADMIN_ID, "all");
    assertThat(JsonPath.read(json, "$.facets.owners[*].id").toString())
        .contains(MEMBER_ID.toString());

    String byOwner = list(ADMIN_ID, "all", "ownerId", MEMBER_ID.toString(), "scope", "all");
    assertThat(titles(byOwner)).contains("Still in review");

    // A format nothing matches empties the page without emptying the workspace.
    String markdown = list(ADMIN_ID, "all", "format", "md", "scope", "all");
    assertThat(titles(markdown)).isEmpty();
    assertThat(((Number) JsonPath.read(markdown, "$.facets.totalUnfiltered")).intValue())
        .isGreaterThan(0);
  }

  /** Raises one open annotation on version 1, authored by {@code author}. */
  private void annotate(UUID documentId, UUID author) throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/documents/" + documentId + "/annotations"), author)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"versionNumber\":1,\"anchor\":{\"region\":{\"surfaceIndex\":0,"
                        + "\"box\":{\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.05}},"
                        + "\"textQuote\":{\"quote\":\"the clause\"}},\"comment\":\"A concern\"}"))
        .andExpect(status().isCreated());
  }
}
