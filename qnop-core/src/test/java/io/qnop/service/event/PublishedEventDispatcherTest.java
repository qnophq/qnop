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
package io.qnop.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.entity.Document;
import io.qnop.repository.DocumentRepository;
import io.qnop.service.review.ReviewEvent;
import io.qnop.spi.event.PublishedEvent;
import io.qnop.spi.event.PublishedEventListener;
import io.qnop.spi.event.PublishedEventTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * DB-free tests for the published event seam (issue #685, ADR-0059): the catalogue mapping and the
 * isolation guarantee, with the test-only listeners the seam issues require.
 */
class PublishedEventDispatcherTest {

  private final UUID doc = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final UUID annotation = UUID.randomUUID();
  private final Instant at = Instant.parse("2026-08-31T10:00:00Z");

  /** The test-only fake consumer proving the seam without an enterprise build. */
  private static final class Recorder implements PublishedEventListener {
    final List<PublishedEvent> heard = new ArrayList<>();

    @Override
    public void on(PublishedEvent event) {
      heard.add(event);
    }
  }

  @Test
  void mapsEveryCataloguedEventWithIdentifiersOnly() {
    UUID comment = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID team = UUID.randomUUID();

    assertThat(map(new ReviewEvent.AnnotationCreated(doc, actor, annotation)))
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo(PublishedEventTypes.ANNOTATION_CREATED);
              assertThat(e.documentId()).isEqualTo(doc);
              assertThat(e.actorId()).isEqualTo(actor);
              assertThat(e.occurredAt()).isEqualTo(at);
              assertThat(e.attributes())
                  .containsExactlyInAnyOrderEntriesOf(
                      Map.of("annotationId", annotation.toString()));
            });
    assertThat(map(new ReviewEvent.AnnotationDecided(doc, actor, annotation, true)).attributes())
        .containsEntry("reopened", "true");
    assertThat(map(new ReviewEvent.AnnotationDismissed(doc, actor, annotation)).type())
        .isEqualTo(PublishedEventTypes.ANNOTATION_DISMISSED);
    assertThat(map(new ReviewEvent.CommentAdded(doc, actor, annotation, comment)).attributes())
        .containsEntry("commentId", comment.toString());
    assertThat(map(new ReviewEvent.VersionUploaded(doc, actor, 4)).attributes())
        .containsEntry("versionNumber", "4");
    assertThat(map(new ReviewEvent.WorkflowChanged(doc, actor, "DRAFT", "IN_REVIEW", true)))
        .satisfies(
            e ->
                assertThat(e.attributes())
                    .containsExactlyInAnyOrderEntriesOf(
                        Map.of("fromState", "DRAFT", "toState", "IN_REVIEW", "manual", "true")));
    assertThat(map(new ReviewEvent.ParticipantAdded(doc, actor, null, team)).attributes())
        .containsExactlyInAnyOrderEntriesOf(Map.of("teamId", team.toString()));
    // Deliberately no title attribute — customer content stays out of the stream.
    assertThat(map(new ReviewEvent.ReviewDeleted(doc, actor, owner, "Secret contract", false)))
        .satisfies(
            e ->
                assertThat(e.attributes())
                    .containsExactlyInAnyOrderEntriesOf(Map.of("ownerId", owner.toString())));
  }

  @Test
  void aThrowingListenerCostsItselfNeverItsSiblingsOrTheCaller() {
    Recorder healthy = new Recorder();
    PublishedEventListener broken =
        event -> {
          throw new IllegalStateException("extension breakage");
        };
    PublishedEventDispatcher dispatcher =
        new PublishedEventDispatcher(List.of(broken, healthy), documents(false));

    dispatcher.on(new ReviewEvent.AnnotationCreated(doc, actor, annotation));

    assertThat(healthy.heard).hasSize(1);
    assertThat(healthy.heard.getFirst().type()).isEqualTo(PublishedEventTypes.ANNOTATION_CREATED);
  }

  @Test
  void deliversToEveryListenerInRegistrationOrder() {
    Recorder first = new Recorder();
    Recorder second = new Recorder();
    PublishedEventDispatcher dispatcher =
        new PublishedEventDispatcher(List.of(first, second), documents(false));

    dispatcher.on(new ReviewEvent.CommentAdded(doc, actor, annotation, UUID.randomUUID()));

    assertThat(first.heard).hasSize(1);
    assertThat(second.heard).hasSize(1);
  }

  @Test
  void withoutListenersNothingIsMapped() {
    // The Community default: dispatcher present, nobody listening, zero work.
    DocumentRepository repository = Mockito.mock(DocumentRepository.class);
    new PublishedEventDispatcher(List.of(), repository)
        .on(new ReviewEvent.AnnotationCreated(doc, actor, annotation));
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void mapsTheUserVariantOfParticipantAdded() {
    UUID user = UUID.randomUUID();
    assertThat(map(new ReviewEvent.ParticipantAdded(doc, actor, user, null)).attributes())
        .containsExactlyInAnyOrderEntriesOf(Map.of("userId", user.toString()));
  }

  @Test
  void anonymousReviewsNeverPublishTheActor() {
    Recorder recorder = new Recorder();
    new PublishedEventDispatcher(List.of(recorder), documents(true))
        .on(new ReviewEvent.AnnotationCreated(doc, actor, annotation));
    assertThat(recorder.heard.getFirst().actorId()).isNull();

    // The deletion event carries the flag itself — nothing is left to load.
    Recorder deleted = new Recorder();
    new PublishedEventDispatcher(List.of(deleted), documents(false))
        .on(new ReviewEvent.ReviewDeleted(doc, actor, UUID.randomUUID(), "t", true));
    assertThat(deleted.heard.getFirst().actorId()).isNull();
  }

  @Test
  void anonymousReviewsStripSubjectIdentitiesToo() {
    // The roster is the one list an anonymous review refuses to reveal (ADR-0038 #422):
    // participant.added must not hand it to an out-of-process consumer, principal by principal.
    Recorder recorder = new Recorder();
    new PublishedEventDispatcher(List.of(recorder), documents(true))
        .on(new ReviewEvent.ParticipantAdded(doc, actor, UUID.randomUUID(), null));
    PublishedEvent heard = recorder.heard.getFirst();
    assertThat(heard.actorId()).isNull();
    assertThat(heard.attributes()).isEmpty();
  }

  @Test
  void anUnloadableDocumentCountsAsAnonymous() {
    Recorder recorder = new Recorder();
    DocumentRepository repository = Mockito.mock(DocumentRepository.class);
    Mockito.when(repository.findById(doc)).thenReturn(Optional.empty());
    new PublishedEventDispatcher(List.of(recorder), repository)
        .on(new ReviewEvent.AnnotationCreated(doc, actor, annotation));
    assertThat(recorder.heard.getFirst().actorId()).isNull();
  }

  private DocumentRepository documents(boolean anonymous) {
    Document document = Mockito.mock(Document.class);
    Mockito.when(document.isAnonymous()).thenReturn(anonymous);
    DocumentRepository repository = Mockito.mock(DocumentRepository.class);
    Mockito.when(repository.findById(doc)).thenReturn(Optional.of(document));
    return repository;
  }

  private PublishedEvent map(ReviewEvent event) {
    return PublishedEventMapper.map(event, at, false);
  }
}
