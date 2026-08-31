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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.Document;
import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMemberProjection;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.service.document.DocumentAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the read-only review facade (issue #602, ADR-0062): every answer delegates to
 * the code path the core itself trusts — the test-only consumer the seam issues require.
 */
class ReviewFacadeServiceTest {

  private final DocumentAccessService access = mock(DocumentAccessService.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ReviewParticipantRepository participants = mock(ReviewParticipantRepository.class);
  private final TeamMembershipRepository teamMembers = mock(TeamMembershipRepository.class);
  private final ReviewIdentityResolver identity = mock(ReviewIdentityResolver.class);
  private final ReviewFacadeService facade =
      new ReviewFacadeService(access, documents, participants, teamMembers, identity);

  private final UUID documentId = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  @Test
  void mayViewDelegatesToTheOneVisibilityRuleWithoutAdminOverride() {
    when(access.isVisible(documentId, owner, false)).thenReturn(true);
    assertThat(facade.mayView(documentId, owner)).isTrue();
    assertThat(facade.mayView(documentId, UUID.randomUUID())).isFalse();
    assertThat(facade.mayView(null, owner)).isFalse();
    assertThat(facade.mayView(documentId, null)).isFalse();
  }

  @Test
  void reviewCircleIsOwnerPlusDirectUsersPlusTeamMembers() {
    UUID direct = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Document document = mock(Document.class);
    when(document.getOwnerId()).thenReturn(owner);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(participants.findByDocumentId(documentId))
        .thenReturn(
            List.of(
                ReviewParticipant.forUser(documentId, direct),
                ReviewParticipant.forTeam(documentId, teamId),
                // An account-less participant (ADR-0061) has no notification identity and
                // deliberately stays out of the circle.
                ReviewParticipant.forExternal(documentId, "Guest")));
    TeamMemberProjection projection = mock(TeamMemberProjection.class);
    when(projection.userId()).thenReturn(member);
    when(teamMembers.findMembersByTeamId(teamId)).thenReturn(List.of(projection));

    assertThat(facade.reviewCircle(documentId)).containsExactly(owner, direct, member);
  }

  @Test
  void identityAnswersComeFromTheAdr0038Resolver() {
    UUID viewer = UUID.randomUUID();
    UUID author = UUID.randomUUID();
    UUID exposed = UUID.randomUUID();
    ReviewIdentityResolver.ReviewIdentities identities =
        mock(ReviewIdentityResolver.ReviewIdentities.class);
    when(identity.forDocument(documentId, viewer)).thenReturn(identities);
    when(identities.displayName(author)).thenReturn("Participant 2");
    when(identities.exposedAuthorId(author)).thenReturn(exposed);

    assertThat(facade.displayNameFor(documentId, viewer, author)).isEqualTo("Participant 2");
    assertThat(facade.exposedAuthorIdFor(documentId, viewer, author)).isEqualTo(exposed);

    when(identities.displayName(author)).thenReturn(null);
    assertThat(facade.displayNameFor(documentId, viewer, author)).isEqualTo("A participant");
  }
}
