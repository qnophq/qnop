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
package io.qnop.service.review.export;

import io.qnop.service.document.DocumentAttachmentService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the images a comment body references, so an export can embed them (issue #635 follow-up).
 *
 * <p>A screenshot pasted into a review is frequently the substance of the comment; an export that
 * dropped it kept the sentence and lost what it pointed at.
 *
 * <p>Two rules make this safe to run on user-authored text. Only this app's own attachment URLs are
 * followed — an export must never become a fetcher for whatever URL someone typed into a comment,
 * which would be a server-side request forgery with a review as the delivery vehicle. And the
 * attachment is always looked up under the document being exported, so a crafted URL naming another
 * document's attachment resolves to nothing rather than to a file the caller cannot otherwise see.
 */
@Component
public class ExportImageResolver {

  private static final Logger log = LoggerFactory.getLogger(ExportImageResolver.class);

  /**
   * {@code /api/v1/documents/{documentId}/attachments/{attachmentId}} — nothing else is fetched.
   */
  private static final Pattern ATTACHMENT_URL =
      Pattern.compile(
          "^/api/v1/documents/(?<document>[0-9a-fA-F-]{36})/attachments/(?<attachment>[0-9a-fA-F-]{36})$");

  /**
   * A ceiling on what one export will embed.
   *
   * <p>Uploads are already capped individually, but a review with a hundred screenshots would
   * otherwise assemble a document nobody can open. Images beyond the budget degrade to their
   * filename, which is what a format that cannot embed them shows anyway.
   */
  private static final long TOTAL_BUDGET_BYTES = 32L * 1024 * 1024;

  private final DocumentAttachmentService attachments;

  public ExportImageResolver(DocumentAttachmentService attachments) {
    this.attachments = attachments;
  }

  /**
   * Resolves every image URL in {@code bodies} that this export may embed.
   *
   * @return url → image, for the URLs that resolved; absent keys are rendered as their alt text
   */
  public Map<String, ExportImage> resolve(
      UUID documentId, Iterable<String> bodies, UUID actor, boolean admin) {
    Map<String, ExportImage> resolved = new LinkedHashMap<>();
    long budget = TOTAL_BUDGET_BYTES;

    for (String body : bodies) {
      for (String url : ExportSegment.imageUrls(body)) {
        if (resolved.containsKey(url)) {
          continue; // the same screenshot quoted twice costs one read
        }
        Optional<UUID> attachmentId = attachmentIdIn(url, documentId);
        if (attachmentId.isEmpty()) {
          continue;
        }
        Optional<ExportImage> image = load(documentId, attachmentId.get(), actor, admin, budget);
        if (image.isPresent()) {
          budget -= image.get().content().length;
          resolved.put(url, image.get());
        }
      }
    }
    return Map.copyOf(resolved);
  }

  /**
   * The attachment id in an app URL, provided it belongs to the document being exported.
   *
   * <p>Anything else — an external URL, a data URI, another document's attachment — yields empty.
   */
  private Optional<UUID> attachmentIdIn(String url, UUID documentId) {
    Matcher matcher = ATTACHMENT_URL.matcher(url);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      if (!UUID.fromString(matcher.group("document")).equals(documentId)) {
        return Optional.empty();
      }
      return Optional.of(UUID.fromString(matcher.group("attachment")));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private Optional<ExportImage> load(
      UUID documentId, UUID attachmentId, UUID actor, boolean admin, long budget) {
    try {
      DocumentAttachmentService.AttachmentMetadata metadata =
          attachments.metadata(documentId, attachmentId, actor, admin);
      if (!DocumentAttachmentService.isInlineImage(metadata.contentType())
          || metadata.sizeBytes() > budget) {
        return Optional.empty();
      }
      byte[] bytes;
      try (DocumentAttachmentService.AttachmentDownload download = attachments.open(metadata)) {
        bytes = download.stream().readAllBytes();
      }
      return embeddable(bytes, metadata.contentType())
          .map(
              converted ->
                  new ExportImage(metadata.fileName(), converted.bytes(), converted.contentType()));
    } catch (RuntimeException | java.io.IOException e) {
      // An image that will not load costs its picture, never the export. The
      // renderer falls back to the filename, so the reader still knows one was here.
      log.warn(
          "Could not load attachment {} for the export of document {}",
          attachmentId,
          documentId,
          e);
      return Optional.empty();
    }
  }

  /**
   * The bytes in a form document formats accept.
   *
   * <p>PNG, JPEG and GIF go in untouched. WEBP does not — no Office format embeds it — so it is
   * decoded and re-encoded as PNG, which is why an ImageIO WEBP reader is on the runtime classpath
   * (ADR-0053).
   */
  private Optional<Converted> embeddable(byte[] bytes, String contentType)
      throws java.io.IOException {
    if (DocumentAttachmentService.PNG.equals(contentType)
        || DocumentAttachmentService.JPEG.equals(contentType)
        || DocumentAttachmentService.GIF.equals(contentType)) {
      return Optional.of(new Converted(bytes, contentType));
    }
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
    if (decoded == null) {
      return Optional.empty();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    return ImageIO.write(decoded, "png", out)
        ? Optional.of(new Converted(out.toByteArray(), DocumentAttachmentService.PNG))
        : Optional.empty();
  }

  private record Converted(byte[] bytes, String contentType) {}
}
