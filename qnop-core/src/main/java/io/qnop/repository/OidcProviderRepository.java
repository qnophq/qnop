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

import io.qnop.entity.OidcProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@link OidcProvider}. */
public interface OidcProviderRepository extends JpaRepository<OidcProvider, UUID> {

  Optional<OidcProvider> findByName(String name);

  /**
   * The stored (still encrypted) client secrets, bypassing the decrypting entity converter — for
   * the re-encryption pass (issue #927), which must see the ciphertext format.
   */
  @Query(
      value =
          "SELECT id AS id, name AS name, client_secret_encrypted AS ciphertext FROM oidc_provider"
              + " WHERE client_secret_encrypted IS NOT NULL AND client_secret_encrypted <> ''",
      nativeQuery = true)
  List<StoredClientSecret> findStoredClientSecrets();

  /**
   * Compare-and-set of one stored client secret (issue #927): replaces the ciphertext only if it is
   * still the one that was read, so a concurrent edit or another instance's pass is never
   * overwritten.
   *
   * @return 1 if replaced, 0 if the stored value had changed in the meantime
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE oidc_provider SET client_secret_encrypted = :next"
              + " WHERE id = :id AND client_secret_encrypted = :previous",
      nativeQuery = true)
  int replaceStoredClientSecret(
      @Param("id") UUID id, @Param("previous") String previous, @Param("next") String next);

  /** A raw stored client secret: the provider and its ciphertext as persisted. */
  interface StoredClientSecret {
    UUID getId();

    String getName();

    String getCiphertext();
  }
}
