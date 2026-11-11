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

import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenID Connect discovery over the guarded outbound client (issue #904).
 *
 * <p>Replaces {@link ClientRegistrations#fromIssuerLocation}, which fetches the discovery document
 * over a private, non-configurable {@code RestTemplate} that resolves the issuer host on its own
 * and follows redirects. Here the document is fetched with the SSRF-guarded client — every resolved
 * address validated, the connection made to exactly those addresses, no redirect followed — and
 * then handed to {@link ClientRegistrations#fromOidcConfiguration}, so Spring Security still builds
 * the registration. The issuer check {@code fromIssuerLocation} performs is kept: the document must
 * name the issuer it was fetched for.
 */
@Component
public class OidcDiscoveryClient {

  static final String OIDC_METADATA_PATH = "/.well-known/openid-configuration";

  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
      new ParameterizedTypeReference<>() {};

  private final RestClient rest;

  @Autowired
  public OidcDiscoveryClient(OidcHttpTransport transport) {
    this(transport.requestFactory());
  }

  OidcDiscoveryClient(ClientHttpRequestFactory requestFactory) {
    this.rest = RestClient.builder().requestFactory(requestFactory).build();
  }

  /**
   * Fetches {@code issuer}'s discovery document and returns a pre-filled registration builder.
   *
   * @throws IllegalArgumentException if the document cannot be fetched (including a refused
   *     destination or a redirect), is not a JSON object, or names a different issuer
   */
  public ClientRegistration.Builder fromIssuer(String issuer) {
    URI location = metadataLocation(issuer);
    Map<String, Object> configuration;
    try {
      configuration =
          rest.get()
              .uri(location)
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .onStatus(
                  status -> !status.is2xxSuccessful(),
                  (request, response) -> {
                    throw new IllegalArgumentException(
                        "The discovery document at "
                            + location
                            + " answered HTTP "
                            + response.getStatusCode().value()
                            + " (qnop reads <issuer>"
                            + OIDC_METADATA_PATH
                            + " and follows no redirects)");
                  })
              .body(JSON_OBJECT);
    } catch (RestClientException e) {
      throw new IllegalArgumentException(
          "Unable to fetch the OpenID configuration of issuer "
              + issuer
              + ": "
              + NestedExceptionUtils.getMostSpecificCause(e).getMessage(),
          e);
    }
    if (configuration == null) {
      throw new IllegalArgumentException("The discovery document of " + issuer + " is empty");
    }
    Object advertised = configuration.get("issuer");
    if (!issuer.equals(advertised)) {
      throw new IllegalArgumentException(
          "The issuer \""
              + advertised
              + "\" in the discovery document does not match the requested issuer \""
              + issuer
              + "\"");
    }
    return ClientRegistrations.fromOidcConfiguration(configuration);
  }

  /**
   * {@code <issuer>/.well-known/openid-configuration} (OpenID Connect Discovery 1.0, §4). An issuer
   * is a URL without query or fragment (§2); one carrying either is refused rather than turned into
   * a wrong location.
   */
  static URI metadataLocation(String issuer) {
    URI parsed;
    try {
      parsed = URI.create(issuer);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("issuerUri is not a valid URI");
    }
    if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
      throw new IllegalArgumentException("issuerUri must not contain a query or fragment");
    }
    String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    return URI.create(base + OIDC_METADATA_PATH);
  }
}
