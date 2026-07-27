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
import static org.mockito.Mockito.when;

import io.qnop.entity.Annotation;
import io.qnop.entity.Comment;
import io.qnop.entity.Document;
import io.qnop.entity.NotificationType;
import io.qnop.entity.User;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.CommentMentionRepository;
import io.qnop.repository.CommentRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * DB-free behavior of {@link ReviewNotificationService} (issues #316/#538): the dispatch contract
 * every sink relies on — ranked candidates, one delivery per recipient per sink, and failure
 * isolation — plus the text utilities. The recipient/anonymity wiring runs against the real stack
 * in {@code ReviewNotificationIT}; the e-mail gates live in {@link MailNotificationSinkTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewNotificationServiceTest {

  @Mock private DocumentRepository documents;
  @Mock private AnnotationRepository annotations;
  @Mock private CommentRepository comments;
  @Mock private CommentMentionRepository commentMentions;
  @Mock private ReviewParticipantRepository participants;
  @Mock private TeamMembershipRepository teamMembers;
  @Mock private UserRepository users;
  @Mock private ApplicationSettingsService settings;
  @Mock private ReviewIdentityResolver identity;

  /** A sink that records what it was handed and can be told to refuse or to blow up. */
  private static final class RecordingSink implements ReviewNotificationSink {
    private final List<ReviewNotificationIntent> delivered = new ArrayList<>();
    private NotificationType refuse;
    private boolean explode;

    @Override
    public boolean accepts(ReviewNotificationIntent intent) {
      return intent.type() != refuse;
    }

    @Override
    public void deliver(ReviewNotificationIntent intent) {
      if (explode) {
        throw new IllegalStateException("sink is down");
      }
      delivered.add(intent);
    }

    List<NotificationType> types() {
      return delivered.stream().map(ReviewNotificationIntent::type).toList();
    }
  }

  private ReviewNotificationService service(ReviewNotificationSink... sinks) {
    return new ReviewNotificationService(
        documents,
        annotations,
        comments,
        commentMentions,
        participants,
        teamMembers,
        users,
        settings,
        identity,
        List.of(sinks));
  }

  @Test
  @DisplayName("a vanished document is quietly nothing to say")
  void vanishedDocument() {
    when(documents.findById(any())).thenReturn(Optional.empty());
    RecordingSink sink = new RecordingSink();

    service(sink)
        .dispatch(
            new ReviewEvent.AnnotationCreated(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

    assertThat(sink.delivered).isEmpty();
  }

  @Test
  @DisplayName("a mentioned follower is told about the mention, not the reply (#462)")
  void mentionOutranksTheReply() {
    RecordingSink sink = new RecordingSink();
    seedMentionedFollower();

    service(sink).dispatch(commentEvent());

    // Both candidates were resolved for the one recipient; only the higher-ranked
    // one is delivered, so nobody hears the same thing twice.
    assertThat(sink.types()).containsExactly(NotificationType.MENTION);
  }

  @Test
  @DisplayName("a sink refusing the mention still gets offered the reply")
  void refusedCandidateFallsThrough() {
    RecordingSink sink = new RecordingSink();
    sink.refuse = NotificationType.MENTION;
    seedMentionedFollower();

    service(sink).dispatch(commentEvent());

    // This is exactly the mail sink's mention opt-out: muting "you were named"
    // must not also mute "someone replied".
    assertThat(sink.types()).containsExactly(NotificationType.COMMENT_ADDED);
  }

  @Test
  @DisplayName("one sink failing never costs the others their delivery")
  void sinkFailureIsIsolated() {
    RecordingSink broken = new RecordingSink();
    broken.explode = true;
    RecordingSink healthy = new RecordingSink();
    seedMentionedFollower();

    service(broken, healthy).dispatch(commentEvent());

    assertThat(healthy.types()).containsExactly(NotificationType.MENTION);
  }

  @Test
  @DisplayName("excerpt flattens markdown and caps the length")
  void excerptFlattens() {
    assertThat(ReviewNotificationService.excerpt("**bold** and [a link](http://x)"))
        .isEqualTo("bold and a link");
    assertThat(ReviewNotificationService.excerpt(null)).isEmpty();
    assertThat(ReviewNotificationService.excerpt("x".repeat(200))).endsWith("…").hasSize(140);
  }

  @Test
  @DisplayName("workflow states read like sentences")
  void humanStates() {
    assertThat(ReviewNotificationService.humanState("CHANGES_REQUESTED"))
        .isEqualTo("Changes requested");
    assertThat(ReviewNotificationService.humanState(null)).isEmpty();
  }

  // --- fixtures ------------------------------------------------------------

  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID ANNOTATION_ID = UUID.randomUUID();
  private static final UUID COMMENT_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID FOLLOWER_ID = UUID.randomUUID();

  private ReviewEvent.CommentAdded commentEvent() {
    return new ReviewEvent.CommentAdded(DOCUMENT_ID, ACTOR_ID, ANNOTATION_ID, COMMENT_ID);
  }

  /** One user who both follows the thread and is mentioned in the new reply. */
  private void seedMentionedFollower() {
    Document document = org.mockito.Mockito.mock(Document.class);
    when(document.getId()).thenReturn(DOCUMENT_ID);
    when(document.getTitle()).thenReturn("Contract");
    when(document.getOwnerId()).thenReturn(ACTOR_ID);
    when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

    Annotation annotation = org.mockito.Mockito.mock(Annotation.class);
    when(annotation.getAuthorId()).thenReturn(FOLLOWER_ID);
    when(annotations.findById(ANNOTATION_ID)).thenReturn(Optional.of(annotation));

    Comment reply = org.mockito.Mockito.mock(Comment.class);
    when(reply.getId()).thenReturn(COMMENT_ID);
    when(reply.getAuthorId()).thenReturn(ACTOR_ID);
    when(reply.getBody()).thenReturn("@follower what do you think?");
    when(comments.findByAnnotationIdOrderByCreatedAtAsc(ANNOTATION_ID)).thenReturn(List.of(reply));

    io.qnop.entity.CommentMention mention =
        org.mockito.Mockito.mock(io.qnop.entity.CommentMention.class);
    when(mention.getMentionedUserId()).thenReturn(FOLLOWER_ID);
    when(commentMentions.findByCommentId(COMMENT_ID)).thenReturn(List.of(mention));

    User follower = org.mockito.Mockito.mock(User.class);
    when(follower.getId()).thenReturn(FOLLOWER_ID);
    when(follower.isEnabled()).thenReturn(true);
    when(follower.getDisplayName()).thenReturn("Fiona Follower");
    when(users.findAllById(any())).thenReturn(List.of(follower));

    ReviewIdentityResolver.ReviewIdentities identities =
        org.mockito.Mockito.mock(ReviewIdentityResolver.ReviewIdentities.class);
    when(identities.displayName(any())).thenReturn("Ada Actor");
    when(identity.forDocument(any(), any())).thenReturn(identities);
  }
}
