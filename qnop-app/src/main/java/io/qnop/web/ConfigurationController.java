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
package io.qnop.web;

import io.qnop.api.v1.endpoint.AdminConfigurationApi;
import io.qnop.api.v1.model.ConfigurationResponse;
import io.qnop.service.config.EffectiveConfigurationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only effective-configuration view ({@code GET /api/v1/admin/configuration}), implementing
 * the generated {@link AdminConfigurationApi} contract (issue #522). Authorization for the {@code
 * /api/v1/admin/**} namespace is enforced centrally in the security filter chain (ADMIN only); the
 * response is redacted by construction, so no secret value ever reaches this layer.
 */
@RestController
public class ConfigurationController implements AdminConfigurationApi {

  private final EffectiveConfigurationService configuration;

  public ConfigurationController(EffectiveConfigurationService configuration) {
    this.configuration = configuration;
  }

  @Override
  public ResponseEntity<ConfigurationResponse> getAdminConfiguration() {
    return ResponseEntity.ok(configuration.effectiveConfiguration());
  }
}
