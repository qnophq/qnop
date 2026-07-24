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
import io.qnop.entity.User;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for mention resolution (issue #462): {@code @slug} tokens resolve through the
 * profile slug, access-scope to the document roster, and stay plain text in anonymous reviews
 * (ADR-0038).
 */
class CommentMentionServiceTest {

  private final CommentMentionRepository mentions = mock(CommentMentionRepository.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final ReviewParticipantRepository participants = mock(ReviewParticipantRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final CommentMentionService service =
      new CommentMentionService(mentions, documents, participants, users);

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

  private void slugResolvesTo(String slug, UUID id) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(id);
    when(users.findBySlugIgnoreCase(slug)).thenReturn(Optional.of(user));
  }

  @Test
  void persistsMentionsOfRosterMembersAndOwnerOnly() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    slugResolvesTo("olivia-owner", owner);
    slugResolvesTo("petra-part", participant);
    slugResolvesTo("sven-stranger", stranger);
    when(participants.existsAccessibleParticipant(documentId, participant)).thenReturn(true);
    when(participants.existsAccessibleParticipant(documentId, stranger)).thenReturn(false);

    var persisted =
        service.resolveAndPersist(
            commentId, documentId, "Hi @olivia-owner @petra-part @sven-stranger");

    // Owner (by owner-id) and the participant resolve; the stranger stays plain text.
    assertThat(persisted).containsExactly(owner, participant);
    verify(mentions).saveAll(any());
  }

  @Test
  void slugLookupIgnoresCaseAndDeduplicates() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    slugResolvesTo("petra-part", participant);
    when(participants.existsAccessibleParticipant(documentId, participant)).thenReturn(true);

    var persisted =
        service.resolveAndPersist(commentId, documentId, "@Petra-Part again @petra-part");

    assertThat(persisted).containsExactly(participant);
  }

  @Test
  void anonymousReviewResolvesNoMentions() {
    Document document = document(true);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));

    var persisted = service.resolveAndPersist(commentId, documentId, "@petra-part");

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
    verify(users, never()).findBySlugIgnoreCase(any());
    verify(participants, never()).existsAccessibleParticipant(eq(documentId), any());
  }

  @Test
  void bodyWithoutTokensNeverTouchesTheDatabase() {
    var persisted =
        service.resolveAndPersist(commentId, documentId, "mail me at a@b.example — ok?");

    assertThat(persisted).isEmpty();
    verify(documents, never()).findById(any());
    verify(mentions, never()).saveAll(any());
  }

  @Test
  void unknownSlugsAndOffRosterMentionsPersistNothing() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(users.findBySlugIgnoreCase("no-such-slug")).thenReturn(Optional.empty());
    slugResolvesTo("sven-stranger", stranger);
    when(participants.existsAccessibleParticipant(documentId, stranger)).thenReturn(false);

    var persisted =
        service.resolveAndPersist(commentId, documentId, "@no-such-slug @sven-stranger");

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
  }
}
