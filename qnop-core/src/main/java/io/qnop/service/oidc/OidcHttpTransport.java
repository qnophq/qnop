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

import io.qnop.service.http.HttpClientProperties;
import io.qnop.service.http.OutboundHttpClients;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;

/**
 * The one SSRF-guarded transport every OIDC request goes through — discovery, token, user-info and
 * JWK set (issue #904). Honours the deployment's private-IdP opt-in ({@link OidcSsrfPolicy}).
 *
 * <p>Deliberately not exposed as a {@code ClientHttpRequestFactory} bean, so nothing else picks it
 * up by type; a holder that owns the pooled client and closes it with the context.
 */
@Component
public class OidcHttpTransport implements DisposableBean {

  private final HttpComponentsClientHttpRequestFactory requestFactory;

  public OidcHttpTransport(OidcSsrfPolicy ssrfPolicy, HttpClientProperties httpProperties) {
    this.requestFactory =
        OutboundHttpClients.requestFactory(httpProperties, ssrfPolicy.allowsPrivate());
  }

  /** The guarded request factory for {@code RestClient}/{@code RestTemplate}. */
  public HttpComponentsClientHttpRequestFactory requestFactory() {
    return requestFactory;
  }

  @Override
  public void destroy() throws Exception {
    requestFactory.destroy();
  }
}
