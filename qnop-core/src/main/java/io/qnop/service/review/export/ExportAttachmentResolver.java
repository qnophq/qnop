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

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
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
 * Resolves the uploads a comment body references, so an export can carry them (#635 follow-up).
 *
 * <p>Images become bytes to embed; other files become metadata to link. Both go through the same
 * participant-gated lookup, because both are the same question: may this reader see this upload?
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
public class ExportAttachmentResolver {

  private static final Logger log = LoggerFactory.getLogger(ExportAttachmentResolver.class);

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

  private final ApplicationSettingsService settings;

  public ExportAttachmentResolver(
      DocumentAttachmentService attachments, ApplicationSettingsService settings) {
    this.attachments = attachments;
    this.settings = settings;
  }

  /**
   * Resolves every image URL in {@code bodies} that this export may embed.
   *
   * @return url → image, for the URLs that resolved; absent keys are rendered as their alt text
   */
  public Map<String, ExportImage> images(
      UUID documentId, Iterable<String> bodies, UUID actor, boolean admin) {
    Map<String, ExportImage> resolved = new LinkedHashMap<>();
    long budget = TOTAL_BUDGET_BYTES;

    for (String body : bodies) {
      for (String url : ExportMarkdown.imageUrls(body)) {
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
   * Resolves every non-image attachment a body links to.
   *
   * <p>Metadata only, plus an <em>absolute</em> link into the app's download page. Absolute is not
   * a nicety: Word resolves a relative target against the document's own location, so {@code
   * /attachments/…} in a downloaded report would become {@code file:///attachments/…} and point at
   * the reader's disk.
   *
   * @param requestOrigin where this download was requested from, used when the operator has not
   *     configured a base URL; null or blank leaves the attachment unlinked
   * @return url → attachment, for the URLs that resolved
   */
  public Map<String, ExportAttachment> files(
      UUID documentId, Iterable<String> bodies, UUID actor, boolean admin, String requestOrigin) {
    Map<String, ExportAttachment> resolved = new LinkedHashMap<>();
    String base = baseUrl(requestOrigin);
    for (String body : bodies) {
      // Images too: Word embeds them, but a spreadsheet cannot, and a reader
      // who sees "[screenshot.png]" with no way to open it is no better off
      // than before.
      for (String url : ExportMarkdown.uploadUrls(body)) {
        if (resolved.containsKey(url)) {
          continue;
        }
        attachmentIdIn(url, documentId)
            .flatMap(id -> describe(documentId, id, actor, admin, base))
            .ifPresent(attachment -> resolved.put(url, attachment));
      }
    }
    return Map.copyOf(resolved);
  }

  private Optional<ExportAttachment> describe(
      UUID documentId, UUID attachmentId, UUID actor, boolean admin, String base) {
    try {
      DocumentAttachmentService.AttachmentMetadata metadata =
          attachments.metadata(documentId, attachmentId, actor, admin);
      // The app's own download page, never the API endpoint behind it. That
      // endpoint wants a bearer token, which a browser following a link out of a
      // Word file has no way to send — the reader would land on a 401 instead of
      // a file. The page authenticates first (logging in and returning here if
      // needed) and only then fetches, with the token attached.
      //
      // No base means no link at all: a relative href would become a file://
      // target on the reader's own machine, which is worse than plain text.
      String href =
          base.isEmpty() ? null : base + "/attachments/" + documentId + "/" + metadata.id();
      return Optional.of(
          new ExportAttachment(
              metadata.fileName(), metadata.contentType(), metadata.sizeBytes(), href));
    } catch (RuntimeException e) {
      log.warn(
          "Could not describe attachment {} for the export of {}", attachmentId, documentId, e);
      return Optional.empty();
    }
  }

  /**
   * The absolute origin to build links from, or empty when there is none.
   *
   * <p>The operator's {@code general.base_url} first, as everywhere else. Where it is unset the
   * origin the export was just downloaded from is used instead — unlike a notification mail, whose
   * link is followed by someone who did not make the request, this link is read by the person who
   * asked for the file, in a browser that set that origin itself. Only when neither exists is the
   * attachment left unlinked, because a relative href is not a dead link: Word turns it into a
   * {@code file://} target on the reader's own disk.
   */
  private String baseUrl(String requestOrigin) {
    String configured = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    if (configured != null && !configured.isBlank()) {
      return trimSlash(configured);
    }
    if (requestOrigin != null && !requestOrigin.isBlank()) {
      log.debug("general.base_url is unset — linking export attachments via the request origin");
      return trimSlash(requestOrigin);
    }
    log.warn("general.base_url is not configured — export attachments will not be linked");
    return "";
  }

  private static String trimSlash(String url) {
    String trimmed = url.strip();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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
