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

import io.qnop.entity.DocumentAttachment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Comment image attachments (issue #446). */
public interface DocumentAttachmentRepository extends JpaRepository<DocumentAttachment, UUID> {

  /** Scoped lookup — an attachment is only addressable under its own document. */
  Optional<DocumentAttachment> findByIdAndDocumentId(UUID id, UUID documentId);

  /** Every attachment's storage key, for the storage-consistency referenced set (issue #523). */
  @Query("SELECT a.storageKey FROM DocumentAttachment a")
  List<String> findAllStorageKeys();

  /**
   * Maps missing storage keys back to their document + file name, to explain a data-loss finding.
   */
  @Query(
      "SELECT new io.qnop.repository.AttachmentStorageRef(a.storageKey, a.documentId, a.fileName)"
          + " FROM DocumentAttachment a WHERE a.storageKey IN :keys")
  List<AttachmentStorageRef> findAttachmentRefsByStorageKeyIn(
      @Param("keys") Collection<String> keys);
}
