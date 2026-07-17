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

import io.qnop.entity.DocumentAttachment;
import io.qnop.repository.DocumentAttachmentRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.storage.StagedObject;
import io.qnop.service.storage.StorageQuotaExceededException;
import io.qnop.service.storage.StorageService;
import io.qnop.spi.storage.StorageContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Files attached to a document's review discussion (issue #446). Upload follows the ingest
 * pipeline's posture: participant-gated (404 for outsiders, anti-enumeration) and the stored type
 * is derived from the magic bytes — the client-declared MIME is never trusted (ADR-0032 §5).
 * Sniffed raster images keep their type and may render inline; sniffed PDF keeps its type;
 * everything else collapses to {@code application/octet-stream} and is served download-only, so an
 * uploaded HTML/SVG payload can never execute in the app origin (same stance as ADR-0031). Bytes
 * stream through the staging registry (ADR-0036): stage → domain row → commit, so a crash in
 * between leaves only an orphan the reaper reclaims. Serving is equally participant-gated — review
 * content is confidential, so unlike avatars the read path is NOT public.
 */
@Service
public class DocumentAttachmentService {

  /** Types that may render inline in a comment body; the migration-0014 CHECK mirrors the set. */
  public static final String PNG = "image/png";

  public static final String JPEG = "image/jpeg";
  public static final String WEBP = "image/webp";
  public static final String GIF = "image/gif";
  public static final Set<String> INLINE_IMAGE_TYPES = Set.of(PNG, JPEG, WEBP, GIF);

  /** Non-image types the sniffing recognises; anything unknown becomes octet-stream. */
  public static final String PDF = "application/pdf";

  public static final String BINARY = "application/octet-stream";

  private static final int SNIFF_LENGTH = 12;
  private static final int MAX_FILE_NAME_LENGTH = 200;
  private static final String FALLBACK_FILE_NAME = "attachment";

  private final DocumentAccessService access;
  private final DocumentAttachmentRepository repository;
  private final StorageService storage;
  private final ApplicationSettingsService settings;

  public DocumentAttachmentService(
      DocumentAccessService access,
      DocumentAttachmentRepository repository,
      StorageService storage,
      ApplicationSettingsService settings) {
    this.access = access;
    this.repository = repository;
    this.storage = storage;
    this.settings = settings;
  }

  /**
   * The admin-configured attachment cap (issue #446 follow-up): {@code
   * upload.attachment_max_file_size_mb}, read live so a settings change applies without a restart.
   * Its registry constraint keeps it below the container's multipart ceiling.
   */
  public long maxAttachmentBytes() {
    return settings.getInteger(ApplicationSettingKey.UPLOAD_ATTACHMENT_MAX_FILE_SIZE_MB)
        * 1024L
        * 1024L;
  }

  /**
   * Validates and stores an uploaded file for the document's discussion. The sequence is
   * stage-then-commit (ADR-0036): the row insert sits between the upload and the commit, so every
   * failure path leaves either nothing or a reapable orphan — never a row without bytes.
   */
  public UploadedAttachment store(
      UUID documentId, UUID actor, boolean admin, UploadSource upload, String declaredFileName) {
    access.getDocument(documentId, actor, admin); // visibility → 404 if not a participant
    long maxBytes = maxAttachmentBytes();
    if (upload.declaredSize() > maxBytes) {
      throw DocumentValidationException.tooLarge("attachment exceeds " + maxBytes + " bytes");
    }
    String contentType = resolveContentType(upload);
    String fileName = sanitizeFileName(declaredFileName);

    StagedObject staged = stageUpload(upload, contentType, maxBytes);
    DocumentAttachment saved =
        repository.save(
            new DocumentAttachment(
                documentId,
                actor,
                fileName,
                contentType,
                staged.contentHash(),
                staged.sizeBytes(),
                staged.key()));
    storage.commit(staged.key());
    return new UploadedAttachment(saved.getId(), fileName, contentType, staged.sizeBytes());
  }

  /** The serving metadata, participant-gated; 404 for outsiders and unknown ids alike. */
  public AttachmentMetadata metadata(
      UUID documentId, UUID attachmentId, UUID actor, boolean admin) {
    access.getDocument(documentId, actor, admin); // visibility → 404 if not a participant
    DocumentAttachment attachment =
        repository
            .findByIdAndDocumentId(attachmentId, documentId)
            .orElseThrow(() -> DocumentValidationException.notFound("unknown attachment"));
    return new AttachmentMetadata(
        attachment.getId(),
        attachment.getFileName(),
        attachment.getContentType(),
        attachment.getContentHash(),
        attachment.getSizeBytes(),
        attachment.getStorageKey());
  }

  /** Opens the attachment's bytes; the caller closes the returned download. */
  public AttachmentDownload open(AttachmentMetadata metadata) {
    StorageContent content =
        storage
            .get(metadata.storageKey())
            .orElseThrow(() -> DocumentValidationException.notFound("attachment content missing"));
    return new AttachmentDownload(content.stream(), content.contentLength());
  }

  /** True when the stored type may render inline (an <img> in the comment body). */
  public static boolean isInlineImage(String contentType) {
    return INLINE_IMAGE_TYPES.contains(contentType);
  }

  /**
   * Derives the stored type from the magic bytes on a fresh stream ({@link UploadSource#open()});
   * the declared header never decides. Recognised raster images and PDF keep their type; every
   * other payload collapses to {@code application/octet-stream}, which is served download-only — so
   * the closed set can never smuggle an executable type.
   */
  private static String resolveContentType(UploadSource upload) {
    byte[] prefix = new byte[SNIFF_LENGTH];
    int read;
    try (InputStream in = upload.open()) {
      read = in.readNBytes(prefix, 0, prefix.length);
    } catch (IOException e) {
      throw DocumentValidationException.invalidRequest("upload could not be read");
    }
    if (read == 0) {
      throw DocumentValidationException.invalidRequest("empty upload");
    }
    if (isPng(prefix, read)) {
      return PNG;
    }
    if (isJpeg(prefix, read)) {
      return JPEG;
    }
    if (isWebp(prefix, read)) {
      return WEBP;
    }
    if (isGif(prefix, read)) {
      return GIF;
    }
    if (isPdf(prefix, read)) {
      return PDF;
    }
    return BINARY;
  }

  /** Streams the upload into storage under the authoritative size cap, mapping its errors. */
  private StagedObject stageUpload(UploadSource upload, String contentType, long maxBytes) {
    try (InputStream in = upload.open()) {
      return storage.stage(in, contentType, maxBytes);
    } catch (StorageQuotaExceededException e) {
      throw DocumentValidationException.tooLarge("attachment exceeds " + e.limitBytes() + " bytes");
    } catch (IOException e) {
      throw DocumentValidationException.invalidRequest("upload could not be read");
    }
  }

  /**
   * Keeps only a harmless display name: the base name without any path, trimmed and length-capped;
   * empty names fall back to a generic label. The name is display metadata only — it never becomes
   * a storage key or filesystem path.
   */
  private static String sanitizeFileName(String declared) {
    if (declared == null || declared.isBlank()) {
      return FALLBACK_FILE_NAME;
    }
    String base = declared.replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).trim();
    if (base.isEmpty()) {
      return FALLBACK_FILE_NAME;
    }
    return base.length() <= MAX_FILE_NAME_LENGTH ? base : base.substring(0, MAX_FILE_NAME_LENGTH);
  }

  private static boolean isPng(byte[] b, int len) {
    byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    if (len < sig.length) {
      return false;
    }
    for (int i = 0; i < sig.length; i++) {
      if (b[i] != sig[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isJpeg(byte[] b, int len) {
    return len >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
  }

  private static boolean isWebp(byte[] b, int len) {
    return len >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  private static boolean isGif(byte[] b, int len) {
    return len >= 6
        && b[0] == 'G'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == '8'
        && (b[4] == '7' || b[4] == '9')
        && b[5] == 'a';
  }

  private static boolean isPdf(byte[] b, int len) {
    return len >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
  }

  /** Outcome of an upload — what the composer needs to build the Markdown reference. */
  public record UploadedAttachment(UUID id, String fileName, String contentType, long sizeBytes) {}

  /** Serving metadata; the content hash doubles as the immutable ETag. */
  public record AttachmentMetadata(
      UUID id,
      String fileName,
      String contentType,
      String contentHash,
      long sizeBytes,
      String storageKey) {}

  /**
   * An open handle on the attachment's bytes — the SPI type stays inside the service layer
   * (ADR-0004), mirroring {@code OriginalDownload}. Close it (or hand the stream to the framework).
   */
  public record AttachmentDownload(InputStream stream, long contentLength)
      implements AutoCloseable {

    @Override
    public void close() {
      try {
        stream.close();
      } catch (IOException e) {
        // best-effort close; nothing actionable
      }
    }
  }
}
