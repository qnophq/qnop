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
package io.qnop.repository;

import io.qnop.entity.Reaction;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Emoji reactions on annotations and comments (issue #410). */
public interface ReactionRepository extends JpaRepository<Reaction, UUID> {

  /** All reactions of a batch of annotations, oldest first — grouped in the service (#313). */
  List<Reaction> findByAnnotationIdInOrderByCreatedAtAsc(Collection<UUID> annotationIds);

  /** All reactions of a batch of comments, oldest first — grouped in the service (#313). */
  List<Reaction> findByCommentIdInOrderByCreatedAtAsc(Collection<UUID> commentIds);

  boolean existsByAnnotationIdAndUserIdAndEmoji(UUID annotationId, UUID userId, String emoji);

  boolean existsByCommentIdAndUserIdAndEmoji(UUID commentId, UUID userId, String emoji);

  long deleteByAnnotationIdAndUserIdAndEmoji(UUID annotationId, UUID userId, String emoji);

  long deleteByCommentIdAndUserIdAndEmoji(UUID commentId, UUID userId, String emoji);
}
