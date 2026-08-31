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
import io.qnop.spi.mention.MentionContext;
import io.qnop.spi.mention.MentionResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
  // The Community resolver only — the seam (issue #598) must be invisible here.
  private final CommentMentionService service =
      new CommentMentionService(
          mentions, documents, participants, List.of(new UserSlugMentionResolver(users)));

  private final UUID commentId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();
  private final UUID participant = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();

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
            commentId, documentId, author, "Hi @olivia-owner @petra-part @sven-stranger");

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
        service.resolveAndPersist(commentId, documentId, author, "@Petra-Part again @petra-part");

    assertThat(persisted).containsExactly(participant);
  }

  @Test
  void anonymousReviewResolvesNoMentions() {
    Document document = document(true);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));

    var persisted = service.resolveAndPersist(commentId, documentId, author, "@petra-part");

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
    verify(users, never()).findBySlugIgnoreCase(any());
    verify(participants, never()).existsAccessibleParticipant(eq(documentId), any());
  }

  @Test
  void bodyWithoutTokensNeverTouchesTheDatabase() {
    var persisted =
        service.resolveAndPersist(commentId, documentId, author, "mail me at a@b.example — ok?");

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
        service.resolveAndPersist(commentId, documentId, author, "@no-such-slug @sven-stranger");

    assertThat(persisted).isEmpty();
    verify(mentions, never()).saveAll(any());
  }

  /**
   * A contributed resolver (issue #598): the shape an add-on takes to expand a token — here a
   * "team" slug — into a set of user ids. Records the context it was handed.
   */
  private static final class TeamResolver implements MentionResolver {
    final Set<UUID> members;
    MentionContext seen;

    TeamResolver(Set<UUID> members) {
      this.members = members;
    }

    @Override
    public Set<UUID> resolve(MentionContext context, String slug) {
      seen = context;
      return slug.equalsIgnoreCase("platform-team") ? members : Set.of();
    }
  }

  @Test
  void contributedResolverExpandsItsTokenToUserIds() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    slugResolvesTo("petra-part", participant);
    when(users.findBySlugIgnoreCase("platform-team")).thenReturn(Optional.empty());
    UUID member = UUID.randomUUID();
    when(participants.existsAccessibleParticipant(documentId, participant)).thenReturn(true);
    when(participants.existsAccessibleParticipant(documentId, member)).thenReturn(true);
    TeamResolver team = new TeamResolver(Set.of(member, participant));
    CommentMentionService seamed =
        new CommentMentionService(
            mentions, documents, participants, List.of(new UserSlugMentionResolver(users), team));

    var persisted =
        seamed.resolveAndPersist(commentId, documentId, author, "@petra-part @platform-team");

    // The team expands to its members; the participant is deduped across both resolvers.
    assertThat(persisted).containsExactlyInAnyOrder(participant, member);
    assertThat(team.seen).isEqualTo(new MentionContext(documentId, owner, author));
  }

  @Test
  void accessRuleAppliesToContributedIdsToo() {
    Document document = document(false);
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(users.findBySlugIgnoreCase("platform-team")).thenReturn(Optional.empty());
    when(participants.existsAccessibleParticipant(documentId, stranger)).thenReturn(false);
    CommentMentionService seamed =
        new CommentMentionService(
            mentions,
            documents,
            participants,
            List.of(new UserSlugMentionResolver(users), new TeamResolver(Set.of(stranger, owner))));

    var persisted = seamed.resolveAndPersist(commentId, documentId, author, "@platform-team");

    // A resolver names whom a token stands for; it cannot widen who may be mentioned.
    assertThat(persisted).containsExactly(owner);
  }
}
