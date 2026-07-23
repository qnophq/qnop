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
package io.qnop.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Document;
import io.qnop.entity.Team;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.repository.CommentMatchProjection;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.repository.UserTeamProjection;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link LikeSearchService} (issue #540): the LIKE-pattern federation, the
 * quick-group caps and totals, the min-length empty answer, and the team {@code viewable} gate. The
 * scoping itself lives in the delegated repository queries and is covered end-to-end by {@code
 * SearchControllerIT}.
 */
class LikeSearchServiceTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
  private final CommentRepository comments = mock(CommentRepository.class);

  private final LikeSearchService service =
      new LikeSearchService(documents, users, teams, memberships, comments);

  private final UUID actor = UUID.randomUUID();

  private static <T> Page<T> pageOf(List<T> content, long total) {
    return new PageImpl<>(content, PageRequest.of(0, 5), total);
  }

  /** Entity ids are DB-generated; the tests assign them reflectively. */
  private static void setId(Object entity, UUID id) {
    try {
      Field field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("a query below the minimum answers empty groups without touching a repository")
  void shortQueryAnswersEmpty() {
    SearchService.GlobalSearchView view = service.quick(actor, false, " a ");

    assertThat(view.reviews().items()).isEmpty();
    assertThat(view.reviews().total()).isZero();
    assertThat(view.users().total()).isZero();
    assertThat(view.teams().total()).isZero();
    verify(documents, never()).findVisibleToMatchingContent(any(), any(), anyBoolean(), any());
    verify(users, never()).pageEnabledPrincipals(any(), any());

    assertThat(service.reviews(actor, false, "", 0, 20).items()).isEmpty();
    assertThat(service.users(null, 0, 20).total()).isZero();
  }

  @Test
  @DisplayName("quick builds the overview's LIKE pattern, caps each group and carries the totals")
  void quickFederatesWithCapsAndTotals() {
    Document document = new Document(actor, "Payment terms");
    when(documents.findVisibleToMatchingContent(eq(actor), eq("%payment%"), eq(false), any()))
        .thenReturn(pageOf(List.of(document), 12));
    when(users.pageEnabledPrincipals(eq("%payment%"), any())).thenReturn(pageOf(List.of(), 0));
    when(teams.pageEnabledPrincipals(eq("%payment%"), any())).thenReturn(pageOf(List.of(), 0));
    when(comments.findSearchMatches(any(), any(), any(), anyBoolean())).thenReturn(List.of());

    SearchService.GlobalSearchView view = service.quick(actor, false, "  Payment ");

    assertThat(view.reviews().items())
        .extracting(SearchService.ReviewHitView::title)
        .containsExactly("Payment terms");
    assertThat(view.reviews().total()).isEqualTo(12);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(documents)
        .findVisibleToMatchingContent(eq(actor), eq("%payment%"), eq(false), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(SearchService.QUICK_SIZE);
    assertThat(pageable.getValue().getPageNumber()).isZero();
  }

  @Test
  @DisplayName("a discussion match carries the first matching comment as its excerpt")
  void discussionMatchCarriesExcerpt() {
    UUID documentId = UUID.randomUUID();
    Document document = new Document(actor, "Q3 report");
    setId(document, documentId);
    when(documents.findVisibleToMatchingContent(eq(actor), eq("%clause%"), eq(false), any()))
        .thenReturn(pageOf(List.of(document), 1));
    when(users.pageEnabledPrincipals(any(), any())).thenReturn(pageOf(List.of(), 0));
    when(teams.pageEnabledPrincipals(any(), any())).thenReturn(pageOf(List.of(), 0));
    when(comments.findSearchMatches(eq(List.of(documentId)), eq("%clause%"), eq(actor), eq(false)))
        .thenReturn(
            List.of(
                new CommentMatchProjection(documentId, "Please rework the\nliability clause."),
                new CommentMatchProjection(documentId, "The clause again, later.")));

    SearchService.GlobalSearchView view = service.quick(actor, false, "clause");

    // The FIRST match wins, whitespace is flattened, nothing is quoted twice.
    assertThat(view.reviews().items().get(0).excerpt())
        .isEqualTo("Please rework the liability clause.");
  }

  @Test
  @DisplayName("the excerpt windows around the match and marks clipped ends")
  void excerptWindowing() {
    String lead = "x".repeat(200);
    assertThat(LikeSearchService.excerptOf(lead + " needle tail", "needle"))
        .startsWith("…")
        .contains("needle");
    assertThat(LikeSearchService.excerptOf("short needle text", "needle"))
        .isEqualTo("short needle text");
    assertThat(LikeSearchService.excerptOf("needle " + "y".repeat(300), "needle"))
        .endsWith("…")
        .hasSizeLessThanOrEqualTo(LikeSearchService.EXCERPT_MAX + 2);
  }

  @Test
  @DisplayName("team hits are viewable for members and admins, locked for strangers")
  void teamViewableGate() {
    UUID mine = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    Team myTeam = Team.create("Alpha", null);
    setId(myTeam, mine);
    Team foreignTeam = Team.create("Alchemy", null);
    setId(foreignTeam, foreign);
    when(teams.pageEnabledPrincipals(eq("%al%"), any()))
        .thenReturn(pageOf(List.of(myTeam, foreignTeam), 2));
    when(users.pageEnabledPrincipals(any(), any())).thenReturn(pageOf(List.of(), 0));
    when(documents.findVisibleToMatchingContent(any(), any(), anyBoolean(), any()))
        .thenReturn(pageOf(List.of(), 0));
    when(memberships.findTeamsOfUser(actor))
        .thenReturn(List.of(new UserTeamProjection(mine, "Alpha", "alpha", TeamRole.MEMBER)));

    List<SearchService.TeamHitView> hits = service.quick(actor, false, "al").teams().items();
    assertThat(hits).extracting(SearchService.TeamHitView::viewable).containsExactly(true, false);

    // An admin reaches every roster, membership or not.
    List<SearchService.TeamHitView> adminHits = service.quick(actor, true, "al").teams().items();
    assertThat(adminHits)
        .extracting(SearchService.TeamHitView::viewable)
        .containsExactly(true, true);
  }

  @Test
  @DisplayName("the paged variants hand page/size through and echo them in the envelope")
  void pagedEnvelope() {
    User user = User.internal("Mia Member", "mia@example.com", "mia", "h");
    // A one-item last page: 2 full pages before it, so the consistent total is 21
    // (PageImpl normalises an inconsistent total to offset + content size).
    when(users.pageEnabledPrincipals(eq("%mia%"), eq(PageRequest.of(2, 10))))
        .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(2, 10), 21));

    SearchService.PageView<SearchService.UserHitView> page = service.users("Mia", 2, 10);

    assertThat(page.items())
        .extracting(SearchService.UserHitView::displayName)
        .containsExactly("Mia Member");
    assertThat(page.total()).isEqualTo(21);
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
  }
}
