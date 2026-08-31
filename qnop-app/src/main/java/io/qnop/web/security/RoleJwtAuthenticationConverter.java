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
package io.qnop.web.security;

import io.qnop.service.JwtTokenService;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps a verified access token to an {@link org.springframework.security.core.Authentication}, with
 * the user's global role (the {@value JwtTokenService#ROLE_CLAIM} claim) promoted to a single
 * {@code ROLE_<value>} authority (issue #98). This is what makes {@code hasRole("ADMIN")} in {@link
 * SecurityConfiguration} match a real login — without it the token carries no authority and {@code
 * /admin/**} is unreachable.
 *
 * <p>The principal name stays the token subject (the {@code qnop_user.id}). A token with no role
 * claim yields no authority (defensive: such a token can never satisfy a role check).
 */
public class RoleJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private static final String AUTHORITY_PREFIX = "ROLE_";

  /** Authority prefix of a machine principal's scopes (issue #686, ADR-0060) — never ROLE_. */
  private static final String EXT_AUTHORITY_PREFIX = "EXT_";

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
  }

  private Collection<GrantedAuthority> authorities(Jwt jwt) {
    // A machine principal (issue #686): its scopes become EXT_-prefixed authorities and its
    // role claim — should a misbehaving authenticator smuggle one — is ignored entirely, so a
    // contributed principal can never satisfy a hasRole gate.
    if ("machine".equals(jwt.getClaimAsString(DelegatingJwtDecoder.ACTOR_KIND_CLAIM))) {
      List<String> scopes = jwt.getClaimAsStringList(DelegatingJwtDecoder.EXT_SCOPES_CLAIM);
      return scopes == null
          ? List.of()
          : scopes.stream()
              .filter(scope -> scope != null && !scope.isBlank())
              .<GrantedAuthority>map(
                  scope -> new SimpleGrantedAuthority(EXT_AUTHORITY_PREFIX + scope))
              .toList();
    }
    String role = jwt.getClaimAsString(JwtTokenService.ROLE_CLAIM);
    if (role == null || role.isBlank()) {
      return List.of();
    }
    return List.of(new SimpleGrantedAuthority(AUTHORITY_PREFIX + role));
  }
}
