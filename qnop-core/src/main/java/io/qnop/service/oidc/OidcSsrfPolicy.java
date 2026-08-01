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

import io.qnop.service.http.OutboundUriGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for operator-supplied OIDC URIs (issue #21).
 *
 * <p>The check itself lives in {@link OutboundUriGuard}, shared since usage tracking gained a
 * second operator-supplied fetch target (issue #666). What stays here is the part that is about
 * OIDC: which deployment property decides whether a private IdP is reachable.
 *
 * <p>The block can be relaxed for trusted internal IdPs with {@code
 * qnop.auth.oidc.allow-private-discovery-uris=true} (scheme validation still applies).
 */
@Component
public class OidcSsrfPolicy {

  private final boolean allowPrivate;

  public OidcSsrfPolicy(
      @Value("${qnop.auth.oidc.allow-private-discovery-uris:false}") boolean allowPrivate) {
    this.allowPrivate = allowPrivate;
  }

  /**
   * Requires {@code value} to be a syntactically valid http(s) URI whose host is not a blocked
   * (private/loopback/metadata) destination. A blank value is accepted only when {@code !required}.
   *
   * @throws IllegalArgumentException if the value is missing (when required), malformed, not
   *     http(s), or targets a blocked host.
   */
  public void requirePublicHttpUri(String value, String fieldName, boolean required) {
    OutboundUriGuard.requireAllowedHttpUri(value, fieldName, required, allowPrivate);
  }
}
