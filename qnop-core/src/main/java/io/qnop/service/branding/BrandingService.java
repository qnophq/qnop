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
package io.qnop.service.branding;

import io.qnop.entity.ApplicationAsset;
import io.qnop.entity.BrandingSlot;
import io.qnop.repository.ApplicationAssetRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates and stores operator branding assets, and reads them back for public serving (issue
 * #23). The upload pipeline is: sniff the real content type from the bytes (never trust the
 * client-declared header) → enforce the size cap → sanitize SVG → bound the pixel dimensions →
 * SHA-256 → delete-then-insert (one row per slot, ADR-0024).
 */
@Service
public class BrandingService {

  private final ApplicationAssetRepository repository;
  private final TransactionTemplate transactionTemplate;

  public BrandingService(
      ApplicationAssetRepository repository, PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Validates the uploaded bytes and replaces the asset in {@code slot}.
   *
   * <p>The CPU-bound validation pipeline — content-type sniffing, SVG sanitization (XML parsing)
   * and pixel-dimension decoding ({@code ImageIO}) — runs <em>outside</em> any transaction so it
   * does not hold a database connection while parsing potentially adversarial input (issue #48).
   * Only the delete-then-insert is transactional.
   *
   * @throws BrandingValidationException if the type is unsupported, the payload too large, the
   *     image unreadable/oversized, or the SVG unsafe
   */
  public StoredAsset store(String slot, byte[] bytes, UUID uploadedBy) {
    BrandingSlot resolved = resolve(slot);
    String contentType = sniffContentType(bytes);

    if (bytes.length > BrandingLimits.MAX_SIZE_BYTES) {
      throw BrandingValidationException.tooLarge(
          "asset exceeds " + BrandingLimits.MAX_SIZE_BYTES + " bytes");
    }

    byte[] content = bytes;
    if (BrandingLimits.SVG.equals(contentType)) {
      try {
        content = SvgSanitizer.sanitize(bytes);
      } catch (IllegalArgumentException e) {
        throw BrandingValidationException.invalidSvg(e.getMessage());
      }
    }

    enforceDimensions(contentType, content);

    String sha256 = sha256Hex(content);
    byte[] stored = content;
    return transactionTemplate.execute(
        status -> {
          // Flush the delete before the insert: a single flush would order the INSERT before the
          // DELETE (Hibernate's action ordering) and transiently violate the (slot) unique
          // constraint.
          repository.deleteBySlot(resolved);
          repository.flush();
          repository.saveAndFlush(
              ApplicationAsset.create(
                  resolved, contentType, stored, sha256, stored.length, uploadedBy));
          return new StoredAsset(contentType, sha256, stored.length);
        });
  }

  /** The current asset for a slot, for public serving. */
  @Transactional(readOnly = true)
  public Optional<BrandingAsset> get(String slot) {
    return repository
        .findBySlot(resolve(slot))
        .map(
            asset ->
                new BrandingAsset(asset.getContent(), asset.getContentType(), asset.getSha256()));
  }

  /** Removes the asset for a slot (idempotent). */
  @Transactional
  public void delete(String slot) {
    repository.deleteBySlot(resolve(slot));
  }

  private static BrandingSlot resolve(String slot) {
    BrandingSlot resolved = BrandingSlot.fromUrlValue(slot);
    if (resolved == null) {
      throw BrandingValidationException.unknownSlot("unknown branding slot: " + slot);
    }
    return resolved;
  }

  private void enforceDimensions(String contentType, byte[] content) {
    Optional<ImageDimensions> dimensions = ImageDimensions.read(contentType, content);
    if (BrandingLimits.PNG.equals(contentType) && dimensions.isEmpty()) {
      throw BrandingValidationException.invalidImage("unreadable PNG");
    }
    dimensions.ifPresent(
        d -> {
          if (d.width() > BrandingLimits.MAX_DIMENSION_PX
              || d.height() > BrandingLimits.MAX_DIMENSION_PX) {
            throw BrandingValidationException.invalidImage(
                "dimensions exceed " + BrandingLimits.MAX_DIMENSION_PX + "px");
          }
        });
  }

  /** Derives the content type from the magic bytes; the client-declared header is not trusted. */
  private String sniffContentType(byte[] bytes) {
    if (isPng(bytes)) {
      return BrandingLimits.PNG;
    }
    if (isWebp(bytes)) {
      return BrandingLimits.WEBP;
    }
    if (looksLikeSvg(bytes)) {
      return BrandingLimits.SVG;
    }
    throw BrandingValidationException.unsupportedType(
        "only PNG, WebP and (sanitized) SVG are accepted");
  }

  private static boolean isPng(byte[] b) {
    byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    if (b.length < sig.length) {
      return false;
    }
    for (int i = 0; i < sig.length; i++) {
      if (b[i] != sig[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isWebp(byte[] b) {
    return b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  private static boolean looksLikeSvg(byte[] b) {
    String head =
        new String(b, 0, Math.min(b.length, 1024), java.nio.charset.StandardCharsets.UTF_8);
    return head.replace("﻿", "").stripLeading().startsWith("<") && head.contains("<svg");
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Metadata returned after a successful upload. */
  public record StoredAsset(String contentType, String sha256, long sizeBytes) {}

  /** An asset's bytes and serving metadata, for the public read path. */
  public record BrandingAsset(byte[] content, String contentType, String sha256) {}
}
