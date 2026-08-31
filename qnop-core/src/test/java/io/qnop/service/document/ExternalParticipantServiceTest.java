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
package io.qnop.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.ReviewParticipant;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for the {@code ExternalParticipants} facade (issue #684, ADR-0061) — the test-only
 * consumer the seam issues require: what an extension may do, and precisely what it may not.
 */
class ExternalParticipantServiceTest {

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ReviewParticipantRepository participants = mock(ReviewParticipantRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final ExternalParticipantService service =
      new ExternalParticipantService(documents, participants, auditEvents);

  private final UUID documentId = UUID.randomUUID();

  @Test
  void addsAnExternalParticipantAndReturnsItsPrincipalId() {
    when(documents.existsById(documentId)).thenReturn(true);
    when(participants.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0, ReviewParticipant.class));

    service.add(documentId, "  Guest Reviewer  ");

    ArgumentCaptor<ReviewParticipant> captor = ArgumentCaptor.forClass(ReviewParticipant.class);
    verify(participants).save(captor.capture());
    ReviewParticipant saved = captor.getValue();
    assertThat(saved.isExternal()).isTrue();
    assertThat(saved.getExternalDisplayName()).isEqualTo("Guest Reviewer"); // trimmed
    assertThat(saved.getUserId()).isNull();
    assertThat(saved.getTeamId()).isNull();
    verify(auditEvents).save(any());
  }

  @Test
  void refusesUnknownDocumentsAndUnusableNames() {
    when(documents.existsById(documentId)).thenReturn(false);
    assertThatThrownBy(() -> service.add(documentId, "Guest"))
        .isInstanceOf(IllegalArgumentException.class);

    when(documents.existsById(documentId)).thenReturn(true);
    assertThatThrownBy(() -> service.add(documentId, "   "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.add(documentId, "x".repeat(121)))
        .isInstanceOf(IllegalArgumentException.class);
    verify(participants, never()).save(any());
  }

  @Test
  void removesOnlyWhatTheFacadeCreated() {
    ReviewParticipant external = ReviewParticipant.forExternal(documentId, "Guest");
    UUID externalId = UUID.randomUUID();
    when(participants.findById(externalId)).thenReturn(Optional.of(external));

    assertThat(service.remove(documentId, externalId)).isTrue();
    verify(participants).delete(external);

    // A user row is refused — an extension can never manage the account-bearing roster.
    ReviewParticipant user = ReviewParticipant.forUser(documentId, UUID.randomUUID());
    UUID userRowId = UUID.randomUUID();
    when(participants.findById(userRowId)).thenReturn(Optional.of(user));
    assertThatThrownBy(() -> service.remove(documentId, userRowId))
        .isInstanceOf(IllegalArgumentException.class);

    // Unknown, or belonging to another document: a plain false, nothing leaked.
    assertThat(service.remove(documentId, UUID.randomUUID())).isFalse();
  }

  @Test
  void hasAccessDelegatesToTheOneAccessQuery() {
    UUID principal = UUID.randomUUID();
    when(participants.existsAccessibleParticipant(documentId, principal)).thenReturn(true);
    assertThat(service.hasAccess(documentId, principal)).isTrue();
    assertThat(service.hasAccess(documentId, null)).isFalse();
    assertThat(service.hasAccess(null, principal)).isFalse();
  }
}
