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
import io.qnop.service.avatar.AvatarImageValidator.ValidatedImage;
import io.qnop.service.avatar.AvatarStorage.AvatarContent;
import io.qnop.service.avatar.AvatarStorage.NewAvatar;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  private final AvatarImageValidator validator;

  public AvatarService(
      AvatarStorage storage, UserRepository users, AvatarImageValidator validator) {
    this.storage = storage;
    this.users = users;
    this.validator = validator;
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
    ValidatedImage image = validator.validate(bytes);

    storage.put(
        new NewAvatar(
            userId,
            image.contentType(),
            bytes,
            image.sha256(),
            image.sizeBytes(),
            image.width(),
            image.height(),
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
}
