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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.Document;
import io.qnop.entity.DocumentVersion;
import io.qnop.repository.AnnotationPlacementRepository;
import io.qnop.repository.AnnotationRepository;
import io.qnop.repository.DocumentRepository;
import io.qnop.repository.DocumentVersionRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.job.JobService;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageQuotaExceededException;
import io.qnop.service.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for {@link DocumentIngestService} (issue #349): the upload validation, staging, and
 * outbox-enqueue contract exercised entirely against mocked collaborators.
 *
 * <p>The service is deliberately not {@code @Transactional} at the method level — only the domain
 * writes run inside a {@link org.springframework.transaction.support.TransactionTemplate}. Here the
 * {@link PlatformTransactionManager} is a bare mock: {@code getTransaction} returns {@code null}
 * and {@code commit}/{@code rollback} are no-ops, so the template simply runs the callback and
 * propagates whatever it throws — which is exactly what the rollback-mapping tests below rely on.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestServiceTest {

  @Mock private DocumentRepository documents;
  @Mock private DocumentVersionRepository versions;
  @Mock private StorageService storage;
  @Mock private JobService jobs;
  @Mock private ApplicationSettingsService settings;
  @Mock private DocumentAccessService access;
  @Mock private AnnotationRepository annotations;
  @Mock private AnnotationPlacementRepository placements;
  @Mock private PlatformTransactionManager transactionManager;

  private DocumentIngestService service;

  private static final UUID DOC = UUID.randomUUID();
  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID STRANGER = UUID.randomUUID();
  private static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final byte[] PDF_BYTES = "%PDF-1.7\n%âã".getBytes(StandardCharsets.UTF_8);

  private DocumentIngestService newService() {
    return new DocumentIngestService(
        documents,
        versions,
        storage,
        jobs,
        settings,
        access,
        annotations,
        placements,
        transactionManager,
        org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
  }

  // --- request validation (rejected before any storage or settings work) -----

  @Nested
  @DisplayName("createDocument request validation")
  class CreateValidation {

    @Test
    @DisplayName("a blank title is rejected with 400 before any upload work")
    void blankTitleRejected() {
      service = newService();

      assertRejected(
          () -> service.createDocument(OWNER, "   ", pdfUpload(), null, null, false, null), 400);
      verify(settings, never()).getInteger(any());
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("a null title is rejected with 400")
    void nullTitleRejected() {
      service = newService();

      assertRejected(
          () -> service.createDocument(OWNER, null, pdfUpload(), null, null, false, null), 400);
    }

    @Test
    @DisplayName("a title over 500 characters is rejected with 400")
    void overlongTitleRejected() {
      service = newService();
      String tooLong = "x".repeat(501);

      assertRejected(
          () -> service.createDocument(OWNER, tooLong, pdfUpload(), null, null, false, null), 400);
    }

    @Test
    @DisplayName("a dueAt in the past is rejected with 400")
    void pastDueAtRejected() {
      service = newService();
      Instant past = Instant.now().minusSeconds(3600);

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", pdfUpload(), past, null, false, null),
          400);
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("a malformed slug is rejected as a field error (400)")
    void malformedSlugRejected() {
      service = newService();

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", pdfUpload(), null, "ab", false, null),
          400);
      verify(documents, never()).existsBySlugIgnoreCase(any());
    }

    @Test
    @DisplayName("a UUID-shaped slug is rejected (it would be unreachable as a route)")
    void uuidShapedSlugRejected() {
      service = newService();

      assertRejected(
          () ->
              service.createDocument(
                  OWNER, "Contract", pdfUpload(), null, DOC.toString(), false, null),
          400);
    }

    @Test
    @DisplayName("an already-taken slug is 409 SLUG_TAKEN before staging")
    void takenSlugRejected() {
      service = newService();
      when(documents.existsBySlugIgnoreCase("my-slug")).thenReturn(true);

      assertThatThrownBy(
              () ->
                  service.createDocument(
                      OWNER, "Contract", pdfUpload(), null, "my-slug", false, null))
          .isInstanceOfSatisfying(
              DocumentValidationException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(409);
                assertThat(e.getCode()).isEqualTo("SLUG_TAKEN");
              });
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("an unknown thread-participation policy is a field error (400)")
    void unknownThreadPolicyRejected() {
      service = newService();

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", pdfUpload(), null, null, false, "LOUD"),
          400);
    }
  }

  // --- upload sniffing & size cap --------------------------------------------

  @Nested
  @DisplayName("createDocument upload checks")
  class UploadChecks {

    @Test
    @DisplayName("a declared size over the operator limit is 413 before the stream is opened")
    void declaredSizeOverLimitRejected() {
      service = newService();
      when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
          .thenReturn(1);
      UploadSource huge = upload(PDF_BYTES, 2L * 1024 * 1024);

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", huge, null, null, false, null), 413);
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("content without the %PDF- magic bytes is 415, never staged")
    void nonPdfMagicRejected() {
      service = newService();
      when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
          .thenReturn(10);
      UploadSource notPdf = upload("<html> not a pdf".getBytes(StandardCharsets.UTF_8), 16);

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", notPdf, null, null, false, null), 415);
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("an empty upload is 400")
    void emptyUploadRejected() {
      service = newService();
      when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
          .thenReturn(10);

      assertRejected(
          () ->
              service.createDocument(
                  OWNER, "Contract", upload(new byte[0], 0), null, null, false, null),
          400);
    }

    @Test
    @DisplayName("an unreadable upload stream is 400")
    void unreadableUploadRejected() {
      service = newService();
      when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
          .thenReturn(10);
      UploadSource broken =
          new UploadSource() {
            @Override
            public InputStream open() throws IOException {
              throw new IOException("disk gone");
            }

            @Override
            public long declaredSize() {
              return 8;
            }
          };

      assertRejected(
          () -> service.createDocument(OWNER, "Contract", broken, null, null, false, null), 400);
    }
  }

  // --- happy path + staging outcome ------------------------------------------

  @Test
  @DisplayName("a valid upload stages, saves version 1, enqueues extraction, then commits storage")
  void createDocumentHappyPath() {
    service = newService();
    when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
        .thenReturn(10);
    StagedObject staged = new StagedObject("sha256/ab/" + "c".repeat(64), "hash", 123L);
    when(storage.stage(any(), eq(PDF_CONTENT_TYPE), anyLong())).thenReturn(staged);
    when(documents.save(any(Document.class)))
        .thenAnswer(
            inv -> {
              Document saved = inv.getArgument(0);
              setId(saved, DOC);
              return saved;
            });
    when(versions.save(any(DocumentVersion.class)))
        .thenAnswer(
            inv -> {
              DocumentVersion saved = inv.getArgument(0);
              setId(saved, UUID.randomUUID());
              return saved;
            });
    Instant dueAt = Instant.now().plusSeconds(86_400);

    DocumentIngestService.UploadResult result =
        service.createDocument(OWNER, "Contract", pdfUpload(), dueAt, null, false, "OPEN");

    assertThat(result.documentId()).isEqualTo(DOC);
    assertThat(result.versionNumber()).isEqualTo(1);
    assertThat(result.extractionStatus()).isEqualTo("PENDING");
    // Outbox: the extraction job commits with the version row.
    verify(jobs).enqueue(eq(DocumentIngestService.EXTRACTION_JOB_TYPE), any());
    // Storage is committed only after the domain transaction succeeds.
    verify(storage).commit(staged.key());
  }

  @Test
  @DisplayName("a storage quota failure maps to 413 and never commits or enqueues")
  void createDocumentStageQuotaExceeded() {
    service = newService();
    when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
        .thenReturn(10);
    when(storage.stage(any(), eq(PDF_CONTENT_TYPE), anyLong()))
        .thenThrow(new StorageQuotaExceededException(5L * 1024 * 1024));

    assertRejected(
        () -> service.createDocument(OWNER, "Contract", pdfUpload(), null, null, false, null), 413);
    verify(documents, never()).save(any());
    verify(jobs, never()).enqueue(any(), any());
    verify(storage, never()).commit(any());
  }

  @Test
  @DisplayName("a slug race lost to the unique index maps the constraint hit to 409 SLUG_TAKEN")
  void createDocumentSlugRaceMappedTo409() {
    service = newService();
    when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
        .thenReturn(10);
    when(documents.existsBySlugIgnoreCase("my-slug")).thenReturn(false);
    when(storage.stage(any(), eq(PDF_CONTENT_TYPE), anyLong()))
        .thenReturn(new StagedObject("sha256/ab/" + "c".repeat(64), "hash", 10L));
    when(documents.save(any(Document.class)))
        .thenThrow(new DataIntegrityViolationException("uq_document_slug"));

    assertThatThrownBy(
            () ->
                service.createDocument(
                    OWNER, "Contract", pdfUpload(), null, "my-slug", false, null))
        .isInstanceOfSatisfying(
            DocumentValidationException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(409);
              assertThat(e.getCode()).isEqualTo("SLUG_TAKEN");
            });
    verify(storage, never()).commit(any());
  }

  // --- addVersion authorization + versioning ---------------------------------

  @Nested
  @DisplayName("addVersion")
  class AddVersion {

    @Test
    @DisplayName("the owner appends the next version, enqueues extraction, and commits")
    void ownerAppendsNextVersion() {
      service = newService();
      when(documents.findById(DOC)).thenReturn(Optional.of(new Document(OWNER, "Contract")));
      when(settings.getInteger(ApplicationSettingKey.UPLOAD_DOCUMENT_MAX_FILE_SIZE_MB))
          .thenReturn(10);
      StagedObject staged = new StagedObject("sha256/ab/" + "c".repeat(64), "hash", 55L);
      when(storage.stage(any(), eq(PDF_CONTENT_TYPE), anyLong())).thenReturn(staged);
      when(versions.findTopByDocumentIdOrderByVersionNumberDesc(DOC))
          .thenReturn(Optional.of(version(2)));
      when(versions.save(any(DocumentVersion.class)))
          .thenAnswer(
              inv -> {
                DocumentVersion saved = inv.getArgument(0);
                setId(saved, UUID.randomUUID());
                return saved;
              });
      when(annotations.findByDocumentId(DOC)).thenReturn(List.of());

      DocumentIngestService.UploadResult result = service.addVersion(OWNER, DOC, pdfUpload());

      assertThat(result.versionNumber()).isEqualTo(3);
      assertThat(result.extractionStatus()).isEqualTo("PENDING");
      verify(jobs).enqueue(eq(DocumentIngestService.EXTRACTION_JOB_TYPE), any());
      verify(storage).commit(staged.key());
    }

    @Test
    @DisplayName("an unknown document is 404")
    void unknownDocumentIs404() {
      service = newService();
      when(documents.findById(DOC)).thenReturn(Optional.empty());

      assertRejected(() -> service.addVersion(OWNER, DOC, pdfUpload()), 404);
    }

    @Test
    @DisplayName("a visible non-owner is told the action is owner-only (403)")
    void visibleNonOwnerIs403() {
      service = newService();
      when(documents.findById(DOC)).thenReturn(Optional.of(new Document(OWNER, "Contract")));
      when(access.isVisible(DOC, STRANGER, false)).thenReturn(true);

      assertRejected(() -> service.addVersion(STRANGER, DOC, pdfUpload()), 403);
      verify(storage, never()).stage(any(), any(), anyLong());
    }

    @Test
    @DisplayName("an invisible non-owner gets the same 404 as an unknown id")
    void invisibleNonOwnerIs404() {
      service = newService();
      when(documents.findById(DOC)).thenReturn(Optional.of(new Document(OWNER, "Contract")));
      when(access.isVisible(DOC, STRANGER, false)).thenReturn(false);

      assertRejected(() -> service.addVersion(STRANGER, DOC, pdfUpload()), 404);
    }
  }

  // --- helpers ---------------------------------------------------------------

  private static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            DocumentValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
  }

  private static DocumentVersion version(int number) {
    return new DocumentVersion(
        DOC, number, "sha256/ab/key-" + number, "hash-" + number, PDF_CONTENT_TYPE, 42L, OWNER);
  }

  private static UploadSource pdfUpload() {
    return upload(PDF_BYTES, PDF_BYTES.length);
  }

  private static UploadSource upload(byte[] content, long declaredSize) {
    return new UploadSource() {
      @Override
      public InputStream open() {
        return new ByteArrayInputStream(content);
      }

      @Override
      public long declaredSize() {
        return declaredSize;
      }
    };
  }

  /** Sets the Hibernate-generated primary key that only exists after a real persist. */
  private static void setId(Object entity, UUID id) {
    try {
      Field field = entity.getClass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(entity, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not seed entity id in test", e);
    }
  }
}
