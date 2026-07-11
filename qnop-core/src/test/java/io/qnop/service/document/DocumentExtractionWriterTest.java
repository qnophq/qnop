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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.AnnotationPlacement;
import io.qnop.entity.AuditEvent;
import io.qnop.entity.DocumentVersion;
import io.qnop.entity.ExtractionStatus;
import io.qnop.entity.PlacementStatus;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.job.JobEnqueuer;
import io.qnop.service.review.ReanchorJobHandler;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link DocumentExtractionWriter} (issue #325): the transactional write phase must
 * emit exactly one audit event per terminal outcome (with the version id, and the reason on
 * failure), persist the failure reason on the version, and stay idempotent on replay so a
 * crash-and-retry never double-audits.
 */
@ExtendWith(MockitoExtension.class)
class DocumentExtractionWriterTest {

  @Mock private DocumentVersionRepository versions;
  @Mock private AnnotationPlacementRepository placements;
  @Mock private JobEnqueuer jobs;
  @Mock private AuditEventRepository auditEvents;
  @Captor private ArgumentCaptor<AuditEvent> auditCaptor;

  private DocumentExtractionWriter writer;

  private static final UUID VERSION_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    writer = new DocumentExtractionWriter(versions, placements, jobs, auditEvents);
  }

  private DocumentVersion pendingVersion() {
    return new DocumentVersion(
        DOCUMENT_ID, 1, "sha256/abc", "abc", "application/pdf", 10L, UUID.randomUUID());
  }

  // --- success ---------------------------------------------------------------

  @Test
  @DisplayName("success flips to READY and audits extraction.succeeded with the version id")
  void auditsSuccess() {
    DocumentVersion version = pendingVersion();
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));
    when(placements.findByDocumentVersionIdAndStatus(VERSION_ID, PlacementStatus.PENDING))
        .thenReturn(List.of());

    writer.attachRenderedAndChain(VERSION_ID, "{\"surfaces\":[]}");

    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.READY);
    verify(auditEvents).save(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.getDocumentId()).isEqualTo(DOCUMENT_ID);
    assertThat(event.getEventType()).isEqualTo(DocumentExtractionWriter.AUDIT_EXTRACTION_SUCCEEDED);
    assertThat(event.getActorId()).isNull(); // system-generated
    assertThat(readField(event.getDetail(), "versionId")).isEqualTo(VERSION_ID.toString());
    assertThat(event.getDetail()).doesNotContain("reason");
    // No pending placements → no re-anchor job.
    verify(jobs, never()).enqueue(any(), any());
  }

  @Test
  @DisplayName("success with pending placements also chains the re-anchor job")
  void chainsReanchorWhenPendingPlacementsExist() {
    DocumentVersion version = pendingVersion();
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));
    when(placements.findByDocumentVersionIdAndStatus(VERSION_ID, PlacementStatus.PENDING))
        .thenReturn(List.of(new AnnotationPlacement(UUID.randomUUID(), VERSION_ID, "{}")));

    writer.attachRenderedAndChain(VERSION_ID, "{\"surfaces\":[]}");

    verify(jobs).enqueue(eq(ReanchorJobHandler.TYPE), any());
    verify(auditEvents).save(any());
  }

  @Test
  @DisplayName("success replay on an already-READY version is a no-op — no second audit")
  void successIdempotentOnReplay() {
    DocumentVersion version = pendingVersion();
    version.attachRenderedDocument("{}");
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));

    writer.attachRenderedAndChain(VERSION_ID, "{\"surfaces\":[]}");

    verify(versions, never()).save(any());
    verify(auditEvents, never()).save(any());
  }

  // --- failure ---------------------------------------------------------------

  @Test
  @DisplayName("failure persists the reason and audits extraction.failed with versionId + reason")
  void auditsFailureWithReason() {
    DocumentVersion version = pendingVersion();
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));
    when(placements.findByDocumentVersionIdAndStatus(VERSION_ID, PlacementStatus.PENDING))
        .thenReturn(List.of());

    writer.failPermanently(VERSION_ID, "encrypted PDF");

    assertThat(version.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
    assertThat(version.getExtractionFailureReason()).isEqualTo("encrypted PDF");
    verify(auditEvents).save(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.getEventType()).isEqualTo(DocumentExtractionWriter.AUDIT_EXTRACTION_FAILED);
    assertThat(event.getActorId()).isNull();
    assertThat(readField(event.getDetail(), "versionId")).isEqualTo(VERSION_ID.toString());
    assertThat(readField(event.getDetail(), "reason")).isEqualTo("encrypted PDF");
  }

  @Test
  @DisplayName("failure fails every pending placement on the version")
  void failsPendingPlacements() {
    DocumentVersion version = pendingVersion();
    AnnotationPlacement pending = new AnnotationPlacement(UUID.randomUUID(), VERSION_ID, "{}");
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));
    when(placements.findByDocumentVersionIdAndStatus(VERSION_ID, PlacementStatus.PENDING))
        .thenReturn(List.of(pending));

    writer.failPermanently(VERSION_ID, "corrupt");

    assertThat(pending.getStatus()).isEqualTo(PlacementStatus.FAILED);
    verify(placements).save(pending);
  }

  @Test
  @DisplayName("a reason with quotes/newlines is JSON-escaped, not concatenated raw")
  void escapesReasonInDetail() {
    DocumentVersion version = pendingVersion();
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));
    when(placements.findByDocumentVersionIdAndStatus(VERSION_ID, PlacementStatus.PENDING))
        .thenReturn(List.of());
    String nasty = "bad \"quote\" and\nnewline";

    writer.failPermanently(VERSION_ID, nasty);

    verify(auditEvents).save(auditCaptor.capture());
    // The detail round-trips as valid JSON with the reason preserved verbatim.
    assertThat(readField(auditCaptor.getValue().getDetail(), "reason")).isEqualTo(nasty);
  }

  @Test
  @DisplayName("failure replay on an already-FAILED version is a no-op — no second audit")
  void failureIdempotentOnReplay() {
    DocumentVersion version = pendingVersion();
    version.markExtractionFailed("first reason");
    when(versions.findById(VERSION_ID)).thenReturn(Optional.of(version));

    writer.failPermanently(VERSION_ID, "second reason");

    verify(versions, never()).save(any());
    verify(auditEvents, never()).save(any());
    assertThat(version.getExtractionFailureReason()).isEqualTo("first reason"); // unchanged
  }

  @Test
  @DisplayName("a deleted version is a no-op for both outcomes")
  void deletedVersionIsNoOp() {
    when(versions.findById(VERSION_ID)).thenReturn(Optional.empty());

    writer.attachRenderedAndChain(VERSION_ID, "{}");
    writer.failPermanently(VERSION_ID, "gone");

    verify(auditEvents, never()).save(any());
    verify(versions, never()).save(any());
  }

  private static String readField(String json, String field) {
    JsonNode node = JsonMapper.builder().build().readTree(json).path(field);
    return node.isMissingNode() || node.isNull() ? null : node.asText();
  }
}
