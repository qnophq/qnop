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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Document;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for mention resolution (issue #462): access-scoping to the document roster and
 * the anonymity policy (mentions disabled in anonymous reviews, ADR-0038).
 */
class CommentMentionServiceTest {

  private final CommentMentionRepository mentions = mock(CommentMentionRepository.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ReviewParticipantRepository participants = mock(ReviewParticipantRepository.class);
  private final CommentMentionService service =
      new CommentMentionService(mentions, documents, participants);

  private final UUID commentId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();
  private final UUID participant = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();

  private Document document(boolean anonymous) {
    Document document = mock(Document.class);
    when(document.getOwnerId()).thenReturn(owner);
    when(document.isAnonymous()).thenReturn(anonymous);
    return document;
  }

  private static String token(UUID id) {
    return "[@Someone](mention:" + id + ")";
  }

  @Test
  void persistsMentionsOfRosterMembersAndOwnerOnly() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(participants.existsAccessibleParticipant(documentId, participant)).thenReturn(true);
    when(participants.existsAccessibleParticipant(documentId, stranger)).thenReturn(false);
    String body = token(owner) + " " + token(participant) + " " + token(stranger);

    var persisted = service.resolveAndPersist(commentId, documentId, body);

    // Owner (by owner-id) and the participant resolve; the stranger stays plain text.
    assertThat(persisted).containsExactly(owner, participant);
    verify(mentions).saveAll(any());
  }

  @Test
  void anonymousReviewResolvesNoMentions() {
    Document document = document(true);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));

    var persisted = service.resolveAndPersist(commentId, documentId, token(participant));

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
    verify(participants, never()).existsAccessibleParticipant(eq(documentId), any());
  }

  @Test
  void bodyWithoutTokensNeverTouchesTheDatabase() {
    var persisted = service.resolveAndPersist(commentId, documentId, "a plain @comment, no tokens");

    assertThat(persisted).isEmpty();
    verify(documents, never()).findById(any());
    verify(mentions, never()).saveAll(any());
  }

  @Test
  void allMentionsOffRosterPersistNothing() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(participants.existsAccessibleParticipant(documentId, stranger)).thenReturn(false);

    var persisted = service.resolveAndPersist(commentId, documentId, token(stranger));

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
  }
}
