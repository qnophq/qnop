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
package io.qnop.service.oidc;

import java.util.Optional;
import java.util.UUID;

/**
 * Single source of truth for Spring Security {@code registrationId} values (issue #21): the
 * provider row's immutable UUID, used verbatim. The {@code /config} endpoint, the {@link
 * DbClientRegistrationRepository}, and Spring Security's redirect URIs ({@code
 * /oauth2/authorization/{id}}, {@code /login/oauth2/code/{id}}) must all agree on it. The UUID is
 * used (not the editable name) so renaming a provider never changes its registered redirect URI.
 */
public final class OidcRegistrationIds {

  private OidcRegistrationIds() {}

  /** The registrationId for a persisted provider id. */
  public static String of(UUID providerId) {
    return providerId.toString();
  }

  /** Parses a registrationId back to a provider id, if it is a valid UUID. */
  public static Optional<UUID> toProviderId(String registrationId) {
    try {
      return Optional.of(UUID.fromString(registrationId));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
