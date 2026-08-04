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
package io.qnop.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.qnop.entity.Notification;
import io.qnop.entity.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the digest claims, given a list of notifications (issue #680). */
class DigestContentTest {

  private static final UUID DOC_A = UUID.randomUUID();
  private static final UUID DOC_B = UUID.randomUUID();

  @Test
  @DisplayName("counts per document rather than a line per event")
  void countsPerDocument() {
    DigestContent content =
        DigestContent.of(
            List.of(
                notification(DOC_A, NotificationType.ANNOTATION_CREATED, "2026-08-03T06:00:00Z"),
                notification(DOC_A, NotificationType.ANNOTATION_CREATED, "2026-08-03T06:05:00Z"),
                notification(DOC_A, NotificationType.COMMENT_ADDED, "2026-08-03T06:10:00Z"),
                notification(DOC_B, NotificationType.VERSION_UPLOADED, "2026-08-03T06:20:00Z")));

    assertThat(content.total()).isEqualTo(4);
    assertThat(content.documents()).hasSize(2);
    assertThat(content.documents().get(0).counts())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                NotificationType.ANNOTATION_CREATED, 2,
                NotificationType.COMMENT_ADDED, 1));
    assertThat(content.documents().get(0).total()).isEqualTo(3);
  }

  @Test
  @DisplayName("documents keep the order of their first unread notification")
  void oldestFirst() {
    DigestContent content =
        DigestContent.of(
            List.of(
                notification(DOC_B, NotificationType.COMMENT_ADDED, "2026-08-03T05:00:00Z"),
                notification(DOC_A, NotificationType.COMMENT_ADDED, "2026-08-03T06:00:00Z"),
                notification(DOC_B, NotificationType.COMMENT_ADDED, "2026-08-03T07:00:00Z")));

    assertThat(content.documents().get(0).documentId()).isEqualTo(DOC_B);
    assertThat(content.documents().get(1).documentId()).isEqualTo(DOC_A);
  }

  @Test
  @DisplayName("the watermark is the newest notification included, never the run's clock")
  void watermarkIsTheNewestIncluded() {
    // The distinction that keeps a notification written mid-run from falling into
    // the gap between the query and the update.
    DigestContent content =
        DigestContent.of(
            List.of(
                notification(DOC_A, NotificationType.COMMENT_ADDED, "2026-08-03T06:00:00Z"),
                notification(DOC_A, NotificationType.COMMENT_ADDED, "2026-08-03T06:42:17Z")));

    assertThat(content.coveredThrough()).isEqualTo(Instant.parse("2026-08-03T06:42:17Z"));
  }

  @Test
  @DisplayName("nothing unread means nothing to send")
  void emptyStaysEmpty() {
    DigestContent content = DigestContent.of(List.of());

    assertThat(content.isEmpty()).isTrue();
    assertThat(content.total()).isZero();
    assertThat(content.coveredThrough()).isNull();
  }

  @Test
  @DisplayName("a notification without a document is counted, not dropped")
  void documentlessIsStillCounted() {
    DigestContent content =
        DigestContent.of(
            List.of(notification(null, NotificationType.MENTION, "2026-08-03T06:00:00Z")));

    assertThat(content.total()).isEqualTo(1);
    assertThat(content.documents()).hasSize(1);
    assertThat(content.documents().get(0).documentId()).isNull();
  }

  private static Notification notification(UUID documentId, NotificationType type, String at) {
    Notification notification = mock(Notification.class);
    when(notification.getDocumentId()).thenReturn(documentId);
    when(notification.getType()).thenReturn(type);
    when(notification.getCreatedAt()).thenReturn(Instant.parse(at));
    return notification;
  }
}
