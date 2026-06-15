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

import io.qnop.entity.OidcIdentity;
import io.qnop.entity.OidcProvider;
import io.qnop.entity.User;
import io.qnop.repository.OidcIdentityRepository;
import io.qnop.service.UserService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an OIDC/OAuth2 login to a local user (issue #21), auto-provisioning on first sight.
 *
 * <p>Linking is <strong>strictly by ({@code provider}, {@code subject})</strong> — there is no
 * cross-provider linking by email, which would let a compromised secondary IdP take over an
 * account. An existing identity bumps {@code last_login_at}; a new one provisions a fresh {@code
 * EXTERNAL} user (requires a usable email — see {@link OidcEmailMissingException}) and the linking
 * row.
 */
@Service
@Transactional
public class OidcIdentityService {

  private static final Logger log = LoggerFactory.getLogger(OidcIdentityService.class);

  private final OidcIdentityRepository identities;
  private final UserService userService;

  public OidcIdentityService(OidcIdentityRepository identities, UserService userService) {
    this.identities = identities;
    this.userService = userService;
  }

  /** Returns the local user for this login, provisioning + linking on first sight. */
  public User upsertOnLogin(OidcProvider provider, ResolvedPrincipal principal) {
    UUID providerId = provider.getId();
    Instant now = Instant.now();
    return identities
        .findByOidcProviderIdAndSubject(providerId, principal.subject())
        .map(identity -> userService.bumpLastLogin(identity.getUserId(), now))
        .orElseGet(() -> provision(provider, principal, now));
  }

  private User provision(OidcProvider provider, ResolvedPrincipal principal, Instant now) {
    String email = trimToNull(principal.email());
    if (email == null) {
      throw new OidcEmailMissingException(
          "The '" + provider.getName() + "' login returned no usable email address.");
    }
    String displayName = orElse(trimToNull(principal.displayName()), principal.subject());
    User user = userService.provisionExternal(displayName, email);
    identities.save(new OidcIdentity(provider.getId(), principal.subject(), user.getId()));
    log.info(
        "Provisioned new OIDC identity: provider={} userId={}", provider.getName(), user.getId());
    return userService.bumpLastLogin(user.getId(), now);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String orElse(String value, String fallback) {
    return value == null ? fallback : value;
  }
}
