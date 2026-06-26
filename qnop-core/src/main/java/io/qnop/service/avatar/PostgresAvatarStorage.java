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

import io.qnop.entity.UserAvatar;
import io.qnop.repository.UserAvatarRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-{@code bytea} implementation of {@link AvatarStorage} (ADR-0031). A replace is a bulk
 * delete-then-insert keyed by {@code user_id}, so each upload yields a fresh {@code updated_at}
 * (the row's creation timestamp) and the {@code (user_id)} primary key is never transiently
 * duplicated.
 */
@Component
public class PostgresAvatarStorage implements AvatarStorage {

  private final UserAvatarRepository repository;

  public PostgresAvatarStorage(UserAvatarRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AvatarContent> find(UUID userId) {
    return repository
        .findById(userId)
        .map(a -> new AvatarContent(a.getContentType(), a.getContent(), a.getSha256()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> findUpdatedAt(UUID userId) {
    return repository.findUpdatedAtByUserId(userId);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Instant> findUpdatedAt(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    return repository.findUpdatedAtByUserIdIn(userIds).stream()
        .collect(
            Collectors.toMap(
                UserAvatarRepository.AvatarUpdatedAtView::getUserId,
                UserAvatarRepository.AvatarUpdatedAtView::getUpdatedAt));
  }

  @Override
  @Transactional
  public void put(NewAvatar avatar) {
    // Bulk-delete the current row first (immediate DML) so the re-insert never collides with the
    // (user_id) primary key, then insert the fresh row with a new creation timestamp.
    repository.deleteByUserId(avatar.userId());
    repository.saveAndFlush(
        UserAvatar.create(
            avatar.userId(),
            avatar.contentType(),
            avatar.content(),
            avatar.sha256(),
            avatar.sizeBytes(),
            avatar.width(),
            avatar.height(),
            avatar.updatedBy()));
  }

  @Override
  @Transactional
  public void remove(UUID userId) {
    repository.deleteByUserId(userId);
  }
}
