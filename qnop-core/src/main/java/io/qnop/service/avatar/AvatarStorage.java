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

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage port for profile avatars (issue #117, ADR-0031). The default implementation keeps the
 * bytes in Postgres {@code bytea}; this seam lets a later S3 adapter re-home them once the ADR-0005
 * {@code StorageProvider} SPI is built, without touching the service, endpoints, or frontend.
 */
public interface AvatarStorage {

  /** The user's full avatar bytes for serving, or empty when none is set. */
  Optional<AvatarContent> find(UUID userId);

  /** When the user's avatar was last set (without loading the bytes), or empty when none is set. */
  Optional<Instant> findUpdatedAt(UUID userId);

  /** Batch {@link #findUpdatedAt(UUID)}: an entry only for users that have an avatar. */
  Map<UUID, Instant> findUpdatedAt(Collection<UUID> userIds);

  /** Replaces the user's avatar with the given validated content (current-only, no history). */
  void put(NewAvatar avatar);

  /** Removes the user's avatar (idempotent). */
  void remove(UUID userId);

  /** An avatar's bytes and serving metadata, for the read path. */
  record AvatarContent(String contentType, byte[] content, String sha256) {}

  /** Validated avatar content to persist. */
  record NewAvatar(
      UUID userId,
      String contentType,
      byte[] content,
      String sha256,
      long sizeBytes,
      Integer width,
      Integer height,
      UUID updatedBy) {}
}
