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

import io.qnop.entity.ApplicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link ApplicationSetting}, keyed by its natural {@code setting_key}. The runtime
 * settings service (issue #16) loads the full set on startup and refreshes it after writes.
 */
public interface ApplicationSettingRepository extends JpaRepository<ApplicationSetting, String> {

  /**
   * Compare-and-set of one stored value (issue #927): rewrites the stored form of a secret only if
   * it is still the one that was read, so a concurrent administrator edit or another instance's
   * re-encryption pass is never overwritten. The plaintext is unchanged, so neither the version nor
   * the audit attribution is touched.
   *
   * @return 1 if replaced, 0 if the stored value had changed in the meantime
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "UPDATE application_setting SET setting_value = :next"
              + " WHERE setting_key = :key AND setting_value = :previous",
      nativeQuery = true)
  int replaceStoredValue(
      @Param("key") String key, @Param("previous") String previous, @Param("next") String next);
}
