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
package io.qnop.service.avatar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * The one place avatar bytes are validated (issue #509 generalises what shipped for user avatars in
 * #117). The pipeline mirrors branding (ADR-0028): sniff the real content type from the magic bytes
 * (never trust the client header) → enforce the size cap → bound the pixel dimensions → SHA-256.
 * SVG is rejected — only the rasterized crop the client produces (PNG/JPEG/WebP) is accepted.
 *
 * <p>Owner-agnostic: {@link AvatarService} (users) and {@code TeamAvatarService} (teams) share it
 * so the sniff/decode/hash logic lives once. It is CPU-bound and stateless, called <em>outside</em>
 * any transaction; only the storage write is transactional.
 */
@Component
public class AvatarImageValidator {

  /** Validated avatar metadata derived from the uploaded bytes, ready to persist. */
  public record ValidatedImage(
      String contentType, Integer width, Integer height, String sha256, long sizeBytes) {}

  /**
   * Validates the uploaded bytes, or throws {@link AvatarValidationException} (unsupported type
   * 415, too large 413, unreadable/oversized image 400).
   */
  public ValidatedImage validate(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw AvatarValidationException.invalidImage("empty upload");
    }
    if (bytes.length > AvatarLimits.MAX_SIZE_BYTES) {
      throw AvatarValidationException.tooLarge(
          "avatar exceeds " + AvatarLimits.MAX_SIZE_BYTES + " bytes");
    }
    String contentType = sniffContentType(bytes);
    int[] dimensions = enforceDimensions(contentType, bytes);
    return new ValidatedImage(
        contentType,
        dimensions == null ? null : dimensions[0],
        dimensions == null ? null : dimensions[1],
        sha256Hex(bytes),
        bytes.length);
  }

  /**
   * Bounds the raster dimensions and returns {@code [width, height]}, or {@code null} when the
   * format carries no readable raster dimensions (WebP without an ImageIO plugin) — the size cap
   * still applies.
   */
  private int[] enforceDimensions(String contentType, byte[] bytes) {
    Optional<int[]> dimensions = rasterDimensions(bytes);
    if ((AvatarLimits.PNG.equals(contentType) || AvatarLimits.JPEG.equals(contentType))
        && dimensions.isEmpty()) {
      throw AvatarValidationException.invalidImage("unreadable " + contentType);
    }
    dimensions.ifPresent(
        d -> {
          if (d[0] > AvatarLimits.MAX_DIMENSION_PX || d[1] > AvatarLimits.MAX_DIMENSION_PX) {
            throw AvatarValidationException.invalidImage(
                "dimensions exceed " + AvatarLimits.MAX_DIMENSION_PX + "px");
          }
        });
    return dimensions.orElse(null);
  }

  private static Optional<int[]> rasterDimensions(byte[] bytes) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      return image == null
          ? Optional.empty()
          : Optional.of(new int[] {image.getWidth(), image.getHeight()});
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Derives the content type from the magic bytes; the client-declared header is not trusted. */
  private static String sniffContentType(byte[] b) {
    if (isPng(b)) {
      return AvatarLimits.PNG;
    }
    if (isJpeg(b)) {
      return AvatarLimits.JPEG;
    }
    if (isWebp(b)) {
      return AvatarLimits.WEBP;
    }
    throw AvatarValidationException.unsupportedType("only PNG, JPEG and WebP are accepted");
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

  private static boolean isJpeg(byte[] b) {
    return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
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

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
