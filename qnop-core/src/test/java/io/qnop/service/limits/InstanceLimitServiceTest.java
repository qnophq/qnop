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
package io.qnop.service.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.repository.DocumentRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * The quota arithmetic (issue #673).
 *
 * <p>Two rules carry most of it: a quota of zero is no quota at all, and the check is "is there
 * room for one more" — not "are we over", which would let a full instance take one more of
 * everything.
 */
class InstanceLimitServiceTest {

  private final UserRepository users = mock(UserRepository.class);
  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamMembershipRepository memberships = mock(TeamMembershipRepository.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);

  private InstanceLimitService service(InstanceLimitProperties limits) {
    return new InstanceLimitService(limits, users, teams, memberships, documents);
  }

  @Test
  @DisplayName("an unlimited quota does not even count")
  void unlimitedNeverCounts() {
    InstanceLimitService service = service(InstanceLimitProperties.unlimited());

    assertThatCode(service::requireUserCapacity).doesNotThrowAnyException();
    assertThatCode(service::requireTeamCapacity).doesNotThrowAnyException();
    assertThatCode(service::requireActiveReviewCapacity).doesNotThrowAnyException();
    assertThatCode(() -> service.requireTeamMemberCapacity(UUID.randomUUID()))
        .doesNotThrowAnyException();

    // The point of asserting this rather than only the outcome: a Community
    // deployment must not pay for four count queries per creation to enforce
    // nothing.
    verifyNoInteractions(users, teams, documents, memberships);
  }

  @Test
  @DisplayName("room for one more is allowed; being at the ceiling is not")
  void refusesAtTheCeiling() {
    InstanceLimitService service = service(new InstanceLimitProperties(3, 0, 0, 0));

    when(users.countByEnabledTrue()).thenReturn(2L);
    assertThatCode(service::requireUserCapacity).doesNotThrowAnyException();

    when(users.countByEnabledTrue()).thenReturn(3L);
    assertThatThrownBy(service::requireUserCapacity)
        .isInstanceOf(InstanceLimitExceededException.class)
        .satisfies(
            e -> {
              InstanceLimitExceededException ex = (InstanceLimitExceededException) e;
              assertThat(ex.code()).isEqualTo("USER_LIMIT_EXCEEDED");
              assertThat(ex.maximum()).isEqualTo(3);
              // The number belongs in the message: "refused" alone tells the
              // reader nothing about what to free or buy.
              assertThat(ex.getMessage()).contains("3").contains("user accounts");
            });
  }

  @Test
  @DisplayName("an instance already over its quota refuses rather than allowing one more")
  void refusesWhenAlreadyOver() {
    // Happens whenever an operator lowers a limit below current usage. Existing
    // records stay; the next one does not.
    when(teams.count()).thenReturn(9L);

    assertThatThrownBy(service(new InstanceLimitProperties(0, 5, 0, 0))::requireTeamCapacity)
        .isInstanceOf(InstanceLimitExceededException.class);
  }

  @Test
  @DisplayName("the team quota counts that team, not the instance")
  void teamMembersAreCountedPerTeam() {
    UUID full = UUID.randomUUID();
    UUID roomy = UUID.randomUUID();
    when(memberships.countByTeamId(full)).thenReturn(10L);
    when(memberships.countByTeamId(roomy)).thenReturn(2L);
    InstanceLimitService service = service(new InstanceLimitProperties(0, 0, 10, 0));

    assertThatThrownBy(() -> service.requireTeamMemberCapacity(full))
        .isInstanceOf(InstanceLimitExceededException.class)
        .hasMessageContaining("members in one team");
    assertThatCode(() -> service.requireTeamMemberCapacity(roomy)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("only unfinished reviews are counted against the review quota")
  void activeReviewsExcludeFinishedWork() {
    // "Not closed and not archived", never a list of open state names: the
    // workflow state is an extensible string (ADR-0011), and naming the open
    // states would count an enterprise state — a review awaiting signature —
    // as finished, freeing a seat that is still in use.
    when(documents.countByArchivedAtIsNullAndClosedAtIsNull()).thenReturn(4L);

    assertThatCode(service(new InstanceLimitProperties(0, 0, 0, 5))::requireActiveReviewCapacity)
        .doesNotThrowAnyException();

    when(documents.countByArchivedAtIsNullAndClosedAtIsNull()).thenReturn(5L);
    assertThatThrownBy(
            service(new InstanceLimitProperties(0, 0, 0, 5))::requireActiveReviewCapacity)
        .isInstanceOf(InstanceLimitExceededException.class)
        .hasMessageContaining("active reviews");
  }

  @Test
  @DisplayName("usage reports the fullest team, and says which quotas are unlimited")
  void usageReportsWhatAnAdministratorNeeds() {
    when(users.countByEnabledTrue()).thenReturn(18L);
    when(teams.count()).thenReturn(3L);
    when(memberships.memberCountsLargestFirst(any(Pageable.class))).thenReturn(List.of(9L));
    when(documents.countByArchivedAtIsNullAndClosedAtIsNull()).thenReturn(42L);

    InstanceLimitUsage usage = service(new InstanceLimitProperties(25, 5, 10, 0)).usage();

    assertThat(usage.users().used()).isEqualTo(18);
    assertThat(usage.users().remaining()).hasValue(7);
    // The largest team, not the sum: that is the number that says whether the
    // next invitation fits.
    assertThat(usage.teamMembers().used()).isEqualTo(9);
    assertThat(usage.activeReviews().unlimited()).isTrue();
    assertThat(usage.activeReviews().remaining()).isEmpty();
    verify(memberships, never()).countByTeamId(any());
  }
}
