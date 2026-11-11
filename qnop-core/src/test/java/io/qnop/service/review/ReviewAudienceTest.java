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

import io.qnop.entity.Annotation;
import io.qnop.entity.Document;
import io.qnop.entity.ThreadParticipation;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.ReviewParticipantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The delivery-time audience rules shared by the mail and digest channels (issue #919). */
class ReviewAudienceTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID FORMER = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID ANNOTATION_ID = UUID.randomUUID();

  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final AnnotationRepository annotations = mock(AnnotationRepository.class);
  private final ReviewParticipantRepository participants = mock(ReviewParticipantRepository.class);
  private final ReviewAudience audience = new ReviewAudience(documents, annotations, participants);
  private final Document document = mock(Document.class);
  private final Annotation annotation = mock(Annotation.class);

  @BeforeEach
  void review() {
    when(document.getId()).thenReturn(DOCUMENT_ID);
    when(document.getOwnerId()).thenReturn(OWNER);
    when(document.getThreadParticipation()).thenReturn(ThreadParticipation.OPEN);
    when(annotation.getAuthorId()).thenReturn(AUTHOR);
    when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
    when(annotations.findById(ANNOTATION_ID)).thenReturn(Optional.of(annotation));
    when(participants.existsAccessibleParticipant(DOCUMENT_ID, AUTHOR)).thenReturn(true);
    when(participants.existsAccessibleParticipant(DOCUMENT_ID, REVIEWER)).thenReturn(true);
  }

  @Test
  @DisplayName("the owner and current participants see the review; a former one does not")
  void reviewAudienceIsCurrentAccess() {
    assertThat(audience.canSeeReview(document, OWNER)).isTrue();
    assertThat(audience.canSeeReview(document, REVIEWER)).isTrue();
    assertThat(audience.canSeeReview(document, FORMER)).isFalse();
    assertThat(audience.canSee(DOCUMENT_ID, null, FORMER)).isFalse();
  }

  @Test
  @DisplayName("an OPEN thread is visible to every participant")
  void openThread() {
    assertThat(audience.canSeeThread(document, annotation, REVIEWER)).isTrue();
    assertThat(audience.canSee(DOCUMENT_ID, ANNOTATION_ID, REVIEWER)).isTrue();
  }

  @Test
  @DisplayName("a PRIVATE thread is visible only to its author and the owner")
  void privateThread() {
    when(document.getThreadParticipation()).thenReturn(ThreadParticipation.PRIVATE);

    assertThat(audience.canSeeThread(document, annotation, AUTHOR)).isTrue();
    assertThat(audience.canSeeThread(document, annotation, OWNER)).isTrue();
    assertThat(audience.canSeeThread(document, annotation, REVIEWER)).isFalse();
    assertThat(audience.canSee(DOCUMENT_ID, ANNOTATION_ID, REVIEWER)).isFalse();
  }

  @Test
  @DisplayName("a deleted review or annotation is visible to nobody")
  void vanishedTargets() {
    assertThat(audience.canSee(UUID.randomUUID(), null, OWNER)).isFalse();
    assertThat(audience.canSee(DOCUMENT_ID, UUID.randomUUID(), OWNER)).isFalse();
  }
}
