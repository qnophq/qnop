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

import io.qnop.repository.UserRepository;
import io.qnop.service.avatar.AvatarStorage.AvatarContent;
import io.qnop.service.avatar.AvatarStorage.NewAvatar;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * Validates and stores user profile avatars, and reads them back for serving (issue #117). The
 * upload pipeline mirrors branding (ADR-0028): sniff the real content type from the bytes (never
 * trust the client-declared header) → enforce the size cap → bound the pixel dimensions → SHA-256 →
 * delegate the persist to {@link AvatarStorage}.
 *
 * <p>The CPU-bound validation (content-type sniffing, {@code ImageIO} decode) runs <em>outside</em>
 * any transaction; only the storage write is transactional. SVG is rejected — only the rasterized
 * crop the client produces (PNG/JPEG/WebP) is accepted.
 */
@Service
public class AvatarService {

  private final AvatarStorage storage;
  private final UserRepository users;

  public AvatarService(AvatarStorage storage, UserRepository users) {
    this.storage = storage;
    this.users = users;
  }

  /**
   * Validates the uploaded bytes and replaces {@code userId}'s avatar.
   *
   * @param actor the user performing the upload (self or an admin), recorded as {@code updated_by}
   * @return when the new avatar was stored (its {@code updated_at}), for building the avatar URL
   * @throws AvatarValidationException if the user is unknown, the type unsupported, the payload too
   *     large, or the image unreadable/oversized
   */
  public Instant store(UUID userId, byte[] bytes, UUID actor) {
    if (!users.existsById(userId)) {
      throw AvatarValidationException.userNotFound("no such user: " + userId);
    }
    if (bytes == null || bytes.length == 0) {
      throw AvatarValidationException.invalidImage("empty upload");
    }
    if (bytes.length > AvatarLimits.MAX_SIZE_BYTES) {
      throw AvatarValidationException.tooLarge(
          "avatar exceeds " + AvatarLimits.MAX_SIZE_BYTES + " bytes");
    }

    String contentType = sniffContentType(bytes);
    int[] dimensions = enforceDimensions(contentType, bytes);

    storage.put(
        new NewAvatar(
            userId,
            contentType,
            bytes,
            sha256Hex(bytes),
            bytes.length,
            dimensions == null ? null : dimensions[0],
            dimensions == null ? null : dimensions[1],
            actor));
    // The read-back runs in a separate transaction from the put above, so a concurrent
    // remove/replace could in theory leave no row. Fail with context rather than a bare,
    // undiagnosable NoSuchElementException.
    return storage
        .findUpdatedAt(userId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "avatar was stored but could not be re-read for user " + userId));
  }

  /** The user's avatar bytes for serving, or empty when none is set. */
  public Optional<AvatarContent> get(UUID userId) {
    return storage.find(userId);
  }

  /** Removes the user's avatar (idempotent). */
  public void remove(UUID userId) {
    storage.remove(userId);
  }

  /** When the user's avatar was last set, or empty when none is set. */
  public Optional<Instant> updatedAt(UUID userId) {
    return storage.findUpdatedAt(userId);
  }

  /** Batch {@link #updatedAt(UUID)} for the admin user list. */
  public Map<UUID, Instant> updatedAt(Collection<UUID> userIds) {
    return storage.findUpdatedAt(userIds);
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
