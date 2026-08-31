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

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Document;
import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewVisitRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.document.ReviewParticipantService;
import io.qnop.spi.participant.ExternalParticipants;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account-less participant seam against the real schema (issue #684, ADR-0061): the widened XOR
 * check, the third access leg, the EXTERNAL view row, visit rows keyed by a principal id that is no
 * user, and the ADR-0038 pseudonym for a guest in an anonymous review.
 */
@Transactional
class ExternalParticipantIT extends AbstractIntegrationTest {

  @Autowired ExternalParticipants externalParticipants;
  @Autowired ReviewParticipantService participantService;
  @Autowired DocumentRepository documents;
  @Autowired UserRepository users;
  @Autowired ReviewVisitRepository visits;

  private UUID owner;

  private UUID review(boolean anonymous) {
    String name = "owner-" + UUID.randomUUID();
    User user = User.internal(name, name + "@example.com", "Olivia Owner", "x");
    user.setRole(UserRole.MEMBER);
    owner = users.save(user).getId();
    Document document = new Document(owner, "Guest-reviewed document");
    if (anonymous) {
      document.setAnonymous(true);
    }
    return documents.save(document).getId();
  }

  @Test
  void aGuestParticipatesAccessesAndIsListedAsExternal() {
    UUID documentId = review(false);

    UUID principal = externalParticipants.add(documentId, "Guest Reviewer");

    assertThat(externalParticipants.hasAccess(documentId, principal)).isTrue();
    assertThat(externalParticipants.hasAccess(documentId, UUID.randomUUID())).isFalse();

    var listed =
        participantService.list(documentId, owner, false).stream()
            .filter(ReviewParticipantService.ParticipantView::external)
            .toList();
    assertThat(listed).hasSize(1);
    assertThat(listed.getFirst().displayName()).isEqualTo("Guest Reviewer");
    assertThat(listed.getFirst().slug()).isNull();
    assertThat(listed.getFirst().principalId()).isEqualTo(principal);

    // Visits key by principal id — no user row, no FK, no hole in "who saw this" (issue #684).
    visits.upsert(UUID.randomUUID(), documentId, principal, Instant.now());
    assertThat(visits.findByDocumentIdAndUserId(documentId, principal)).isPresent();

    assertThat(externalParticipants.remove(documentId, principal)).isTrue();
    assertThat(externalParticipants.hasAccess(documentId, principal)).isFalse();
  }

  @Test
  void anAnonymousReviewPseudonymizesTheGuestLikeAnyReviewer() {
    UUID documentId = review(true);
    UUID principal = externalParticipants.add(documentId, "Guest Reviewer");
    // The pseudonym rule exempts the owner (ADR-0038 #422) — so the assertion must look through
    // a NON-owner participant's eyes, where anonymity actually applies.
    User fellow =
        User.internal(
            "fellow-" + UUID.randomUUID(),
            "f" + UUID.randomUUID() + "@example.com",
            "Fellow Reviewer",
            "x");
    fellow.setRole(UserRole.MEMBER);
    UUID fellowId = users.save(fellow).getId();
    participantService.add(documentId, owner, false, fellowId, null);

    var listed =
        participantService.list(documentId, fellowId, false).stream()
            .filter(ReviewParticipantService.ParticipantView::external)
            .toList();

    assertThat(listed).hasSize(1);
    // The supplied display name never pierces ADR-0038 — the guest reads as "Participant N".
    assertThat(listed.getFirst().displayName()).startsWith("Participant ");
    assertThat(listed.getFirst().principalId()).isNotEqualTo(principal);
  }
}
