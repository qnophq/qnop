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

import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.review.AnnotationService;
import io.qnop.spi.review.ReviewFacade;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the facade to the listing endpoint's authorization (issue #602, ADR-0062): for owner, direct
 * participant, team member and outsider, {@code mayView} answers exactly as the annotation-listing
 * service does — the two rules cannot drift because they are one rule.
 */
@Transactional
class ReviewFacadeIT extends SeededIntegrationTest {

  @Autowired ReviewFacade facade;
  @Autowired AnnotationService annotations;
  @Autowired DocumentRepository documents;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired TeamMembershipRepository memberships;
  @Autowired ReviewParticipantRepository participants;

  private UUID user(String name) {
    String unique = name + "-" + UUID.randomUUID();
    User user = User.internal(unique, unique + "@example.com", unique, "x");
    user.setRole(UserRole.MEMBER);
    return users.save(user).getId();
  }

  @Test
  void mayViewMatchesTheAnnotationListingRuleForEveryRole() {
    UUID owner = user("owner");
    UUID direct = user("direct");
    UUID teamMember = user("member");
    UUID outsider = user("outsider");
    UUID documentId = documents.save(new Document(owner, "Facade-pinned review")).getId();
    Team team = teams.save(Team.create("facade-team-" + UUID.randomUUID(), null));
    memberships.save(TeamMembership.of(team.getId(), teamMember, TeamRole.MEMBER));
    participants.save(ReviewParticipant.forUser(documentId, direct));
    participants.save(ReviewParticipant.forTeam(documentId, team.getId()));

    for (UUID allowed : new UUID[] {owner, direct, teamMember}) {
      assertThat(facade.mayView(documentId, allowed)).isTrue();
      // The listing service answers the same way: no throw.
      annotations.list(documentId, null, null, null, allowed, false);
    }
    assertThat(facade.mayView(documentId, outsider)).isFalse();
    assertThatThrownBy(() -> annotations.list(documentId, null, null, null, outsider, false))
        .isInstanceOf(RuntimeException.class);

    assertThat(facade.reviewCircle(documentId))
        .containsExactlyInAnyOrder(owner, direct, teamMember);
    // ADR-0061: an account-less principal passes mayView via its row id but stays out of the
    // circle (no notification identity).
    UUID guest =
        participants.save(ReviewParticipant.forExternal(documentId, "Guest Reviewer")).getId();
    assertThat(facade.mayView(documentId, guest)).isTrue();
    assertThat(facade.reviewCircle(documentId)).doesNotContain(guest);
    // ADR-0038 identity flows through the facade for a normal review: real name visible.
    assertThat(facade.displayNameFor(documentId, direct, owner)).isNotBlank();
  }
}
