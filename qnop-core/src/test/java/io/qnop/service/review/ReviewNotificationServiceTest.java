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
import static org.mockito.Mockito.mock;
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
  @Mock private io.qnop.repository.UserSettingRepository userSettings;

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
        new ReviewFacadeService(
            mock(io.qnop.service.document.DocumentAccessService.class),
            documents,
            participants,
            teamMembers,
            identity),
        new io.qnop.service.mail.MailLocaleResolver(
            userSettings, settings, new io.qnop.service.mail.MailTemplateDefaults()),
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
    io.qnop.service.mail.MailMessages english = io.qnop.service.mail.MailMessages.forLocale("en");
    assertThat(english.workflowState("CHANGES_REQUESTED")).isEqualTo("Changes requested");
    assertThat(english.workflowState(null)).isEmpty();
  }

  // --- the recipient's mail language (issue #853) ---------------------------

  private void prefersGerman(UUID userId) {
    io.qnop.entity.UserSetting setting = org.mockito.Mockito.mock(io.qnop.entity.UserSetting.class);
    when(setting.getSettingValue()).thenReturn("de");
    when(userSettings.findByUserIdAndSettingKey(
            userId, io.qnop.service.UserSettingKey.PREFERRED_LANGUAGE.getKey()))
        .thenReturn(Optional.of(setting));
  }

  @Test
  @DisplayName("a German recipient's decision mail speaks German; the stored decision stays raw")
  void decisionIsWordedInTheRecipientsLanguage() {
    seedMentionedFollower();
    prefersGerman(FOLLOWER_ID);
    RecordingSink sink = new RecordingSink();

    service(sink)
        .dispatch(new ReviewEvent.AnnotationDismissed(DOCUMENT_ID, ACTOR_ID, ANNOTATION_ID));

    ReviewNotificationIntent intent = sink.delivered.getFirst();
    assertThat(intent.mailLocale()).isEqualTo("de");
    assertThat(intent.mailVars()).containsEntry("decision", "verworfen");
    assertThat(intent.decision()).isEqualTo("dismissed");
  }

  @Test
  @DisplayName("an English recipient keeps the English words and an unresolvable actor fallback")
  void englishRecipientKeepsEnglish() {
    seedMentionedFollower();
    ReviewIdentityResolver.ReviewIdentities nobody =
        org.mockito.Mockito.mock(ReviewIdentityResolver.ReviewIdentities.class);
    when(identity.forDocument(any(), any())).thenReturn(nobody);
    RecordingSink sink = new RecordingSink();

    service(sink)
        .dispatch(new ReviewEvent.AnnotationDecided(DOCUMENT_ID, ACTOR_ID, ANNOTATION_ID, false));

    ReviewNotificationIntent intent = sink.delivered.getFirst();
    assertThat(intent.mailLocale()).isEqualTo("en");
    assertThat(intent.mailVars())
        .containsEntry("decision", "resolved")
        .containsEntry("actorName", "A participant");
  }

  @Test
  @DisplayName("workflow states and the actor fallback are German for a German recipient")
  void workflowStatesInGerman() {
    seedMentionedFollower();
    prefersGerman(FOLLOWER_ID);
    when(participants.findByDocumentId(DOCUMENT_ID))
        .thenReturn(List.of(io.qnop.entity.ReviewParticipant.forUser(DOCUMENT_ID, FOLLOWER_ID)));
    RecordingSink sink = new RecordingSink();

    service(sink)
        .dispatch(
            new ReviewEvent.WorkflowChanged(
                DOCUMENT_ID, ACTOR_ID, "IN_REVIEW", "CHANGES_REQUESTED", true));

    assertThat(sink.delivered)
        .singleElement()
        .satisfies(
            intent ->
                assertThat(intent.mailVars())
                    .containsEntry("oldState", "Im Review")
                    .containsEntry("newState", "Änderungen angefordert"));
  }

  @Test
  @DisplayName("every notification's mail variables cover its template's placeholders")
  void everyIntentCoversItsTemplatesPlaceholders() {
    // Strict Mustache drops a mail whose variables miss a placeholder — which is how the
    // review-deleted mail silently never went out before #853. Every intent type is built
    // here and checked against its template's closed placeholder set.
    seedMentionedFollower();
    UUID other = UUID.randomUUID();
    User follower = users.findAllById(List.of()).getFirst();
    when(users.findById(FOLLOWER_ID)).thenReturn(Optional.of(follower));
    List<ReviewEvent> events =
        List.of(
            new ReviewEvent.ParticipantAdded(DOCUMENT_ID, other, FOLLOWER_ID, null),
            new ReviewEvent.AnnotationCreated(DOCUMENT_ID, other, ANNOTATION_ID),
            new ReviewEvent.AnnotationDecided(DOCUMENT_ID, other, ANNOTATION_ID, true),
            new ReviewEvent.AnnotationDismissed(DOCUMENT_ID, other, ANNOTATION_ID),
            new ReviewEvent.CommentAdded(DOCUMENT_ID, other, ANNOTATION_ID, COMMENT_ID),
            new ReviewEvent.VersionUploaded(DOCUMENT_ID, other, 2),
            new ReviewEvent.WorkflowChanged(DOCUMENT_ID, other, "IN_REVIEW", "FINALIZED", true),
            new ReviewEvent.ReviewDeleted(DOCUMENT_ID, other, FOLLOWER_ID, "Contract", false));
    java.util.Set<NotificationType> seen = java.util.EnumSet.noneOf(NotificationType.class);
    for (ReviewEvent event : events) {
      // Once as is, once refusing mentions so the intent ranked below a mention shows up too.
      for (NotificationType refused : new NotificationType[] {null, NotificationType.MENTION}) {
        RecordingSink sink = new RecordingSink();
        sink.refuse = refused;
        service(sink).dispatch(event);
        for (ReviewNotificationIntent intent : sink.delivered) {
          seen.add(intent.type());
          assertThat(intent.mailVars().keySet())
              .as("%s variables", intent.type())
              .containsAll(intent.template().placeholders());
          assertThat(intent.mailLocale()).as("%s language", intent.type()).isEqualTo("en");
        }
      }
    }
    assertThat(seen).containsExactlyInAnyOrder(NotificationType.values());
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
