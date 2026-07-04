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

import io.qnop.entity.OidcProvider;
import io.qnop.entity.OidcProviderType;
import io.qnop.repository.OidcProviderRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of DB-configured OIDC/OAuth2 providers (issue #21, PR A): CRUD plus
 * issuer-discovery. Client secrets are encrypted at rest by the entity converter (issue #11) and
 * never returned (the {@link OidcProviderView} exposes only {@code hasClientSecret}). Every
 * operator-supplied URI passes the {@link OidcSsrfPolicy} before storage or fetch. The browser
 * login flow that consumes these rows arrives in PR B.
 */
@Service
@Transactional
public class OidcProviderService {

  private static final String DISCOVERY_FAILED =
      "OIDC discovery failed — verify the issuer URI is reachable and serves a valid"
          + " openid-configuration document.";
  private static final String DEFAULT_SCOPE = "openid email profile";

  private static final Logger log = LoggerFactory.getLogger(OidcProviderService.class);

  private final OidcProviderRepository providers;
  private final OidcSsrfPolicy ssrfPolicy;
  private final ApplicationEventPublisher events;

  public OidcProviderService(
      OidcProviderRepository providers,
      OidcSsrfPolicy ssrfPolicy,
      ApplicationEventPublisher events) {
    this.providers = providers;
    this.ssrfPolicy = ssrfPolicy;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<OidcProviderView> findAll() {
    return providers.findAll().stream().map(OidcProviderService::toView).toList();
  }

  @Transactional(readOnly = true)
  public OidcProviderView findById(UUID id) {
    return toView(require(id));
  }

  /**
   * The enabled providers as public login-page button projections (issue #21), each carrying the
   * derived brand icon and account-switch affordances ({@link OidcLoginInfoFactory}).
   */
  @Transactional(readOnly = true)
  public List<OidcProviderLoginView> enabledLoginViews() {
    return providers.findAll().stream()
        .filter(OidcProvider::isEnabled)
        .map(OidcLoginInfoFactory::loginView)
        .toList();
  }

  /**
   * Whether the provider honours an OIDC {@code prompt} hint, used by {@link
   * io.qnop.web.security.PromptAwareOAuth2AuthorizationRequestResolver} for the account-switch
   * affordance. True for every type except GitHub, which silently ignores {@code prompt}; an
   * unknown id is {@code false} so the resolver leaves the request untouched.
   */
  @Transactional(readOnly = true)
  public boolean honoursPrompt(UUID id) {
    return providers
        .findById(id)
        .map(p -> p.getProviderType() != OidcProviderType.GITHUB)
        .orElse(false);
  }

  /** Probes an issuer for OIDC discovery support without persisting anything. */
  @Transactional(readOnly = true)
  public OidcDiscoveryOutcome discoverEndpoints(String issuerUri) {
    String trimmed = issuerUri == null ? "" : issuerUri.trim();
    if (trimmed.isEmpty()) {
      return OidcDiscoveryOutcome.failure("issuerUri must not be blank");
    }
    try {
      ssrfPolicy.requirePublicHttpUri(trimmed, "issuerUri", true);
    } catch (IllegalArgumentException e) {
      log.warn("OIDC discovery rejected for issuerUri={}: {}", trimmed, e.getMessage());
      return OidcDiscoveryOutcome.failure(e.getMessage());
    }
    try {
      // fromIssuerLocation fetches the discovery document and pre-fills the endpoints; build()
      // still validates a registrationId + clientId, which a pure endpoint probe does not have.
      // Supply throwaway placeholders so the build succeeds without the operator's real
      // credentials.
      ClientRegistration registration =
          ClientRegistrations.fromIssuerLocation(trimmed)
              .registrationId("discovery")
              .clientId("discovery")
              .build();
      ClientRegistration.ProviderDetails details = registration.getProviderDetails();
      return new OidcDiscoveryOutcome(
          true,
          details.getAuthorizationUri(),
          details.getTokenUri(),
          details.getUserInfoEndpoint() == null ? null : details.getUserInfoEndpoint().getUri(),
          details.getJwkSetUri(),
          null);
    } catch (RuntimeException e) {
      log.debug("OIDC discovery failed for issuerUri={}: {}", trimmed, e.getMessage());
      return OidcDiscoveryOutcome.failure(discoveryFailureMessage(e));
    }
  }

  /**
   * A discovery-failure message that keeps the actionable hint and appends the underlying cause.
   * Discovery is an admin-only probe against an operator-supplied issuer, so the cause (a Spring
   * {@code ClientRegistrations} message, e.g. an unreachable issuer or a malformed document) aids
   * diagnosis without leaking qnop internals.
   */
  private static String discoveryFailureMessage(Throwable cause) {
    String detail = cause.getMessage();
    return detail == null || detail.isBlank()
        ? DISCOVERY_FAILED
        : DISCOVERY_FAILED + " (" + detail + ")";
  }

  /** Creates a disabled provider (the operator enables it after verifying the configuration). */
  public OidcProviderView create(
      String name,
      String providerType,
      String clientId,
      String clientSecret,
      String issuerUri,
      String scope,
      String authorizationUri,
      String tokenUri,
      String userInfoUri,
      String jwkSetUri,
      String userNameAttribute,
      String emailAttribute,
      String displayNameAttribute) {
    if (providers.findByName(name).isPresent()) {
      throw new OidcProviderConflictException(
          "NAME_TAKEN", "A provider with that name already exists.");
    }
    OidcProviderType type = OidcProviderType.valueOf(providerType);
    if (type == OidcProviderType.OAUTH2) {
      ssrfPolicy.requirePublicHttpUri(authorizationUri, "authorizationUri", true);
      ssrfPolicy.requirePublicHttpUri(tokenUri, "tokenUri", true);
      ssrfPolicy.requirePublicHttpUri(userInfoUri, "userInfoUri", true);
      ssrfPolicy.requirePublicHttpUri(jwkSetUri, "jwkSetUri", false);
    }
    ssrfPolicy.requirePublicHttpUri(issuerUri, "issuerUri", type == OidcProviderType.OIDC);

    OidcProvider provider = new OidcProvider(name, type, clientId);
    provider.setClientSecret(clientSecret); // encrypted at rest by the entity converter (#11)
    provider.setEnabled(false);
    provider.setIssuerUri(trimToNull(issuerUri));
    provider.setScope(scope == null || scope.isBlank() ? DEFAULT_SCOPE : scope.trim());
    provider.setAuthorizationUri(trimToNull(authorizationUri));
    provider.setTokenUri(trimToNull(tokenUri));
    provider.setUserInfoUri(trimToNull(userInfoUri));
    provider.setJwkSetUri(trimToNull(jwkSetUri));
    provider.setUserNameAttribute(trimToNull(userNameAttribute));
    provider.setEmailAttribute(trimToNull(emailAttribute));
    provider.setDisplayNameAttribute(trimToNull(displayNameAttribute));
    OidcProviderView view = toView(providers.save(provider));
    events.publishEvent(new OidcProvidersChangedEvent());
    return view;
  }

  /**
   * Applies a partial update; only non-null fields change. A blank secret means "do not change".
   */
  public OidcProviderView update(UUID id, OidcProviderPatch patch) {
    OidcProvider provider = require(id);
    if (patch.enabled() != null) {
      provider.setEnabled(patch.enabled());
    }
    if (isSet(patch.name())) {
      provider.setName(patch.name().trim());
    }
    if (isSet(patch.clientId())) {
      provider.setClientId(patch.clientId().trim());
    }
    if (isSet(patch.clientSecret())) {
      provider.setClientSecret(patch.clientSecret());
    }
    if (patch.issuerUri() != null) {
      ssrfPolicy.requirePublicHttpUri(patch.issuerUri(), "issuerUri", false);
      provider.setIssuerUri(trimToNull(patch.issuerUri()));
    }
    if (isSet(patch.scope())) {
      provider.setScope(patch.scope().trim());
    }
    if (patch.authorizationUri() != null) {
      ssrfPolicy.requirePublicHttpUri(patch.authorizationUri(), "authorizationUri", false);
      provider.setAuthorizationUri(trimToNull(patch.authorizationUri()));
    }
    if (patch.tokenUri() != null) {
      ssrfPolicy.requirePublicHttpUri(patch.tokenUri(), "tokenUri", false);
      provider.setTokenUri(trimToNull(patch.tokenUri()));
    }
    if (patch.userInfoUri() != null) {
      ssrfPolicy.requirePublicHttpUri(patch.userInfoUri(), "userInfoUri", false);
      provider.setUserInfoUri(trimToNull(patch.userInfoUri()));
    }
    if (patch.jwkSetUri() != null) {
      ssrfPolicy.requirePublicHttpUri(patch.jwkSetUri(), "jwkSetUri", false);
      provider.setJwkSetUri(trimToNull(patch.jwkSetUri()));
    }
    if (patch.userNameAttribute() != null) {
      provider.setUserNameAttribute(trimToNull(patch.userNameAttribute()));
    }
    if (patch.emailAttribute() != null) {
      provider.setEmailAttribute(trimToNull(patch.emailAttribute()));
    }
    if (patch.displayNameAttribute() != null) {
      provider.setDisplayNameAttribute(trimToNull(patch.displayNameAttribute()));
    }
    // Explicit save rather than relying on transactional dirty-checking, mirroring create()
    // and keeping the persistence intent visible at the call site (issue #339).
    OidcProviderView view = toView(providers.save(provider));
    events.publishEvent(new OidcProvidersChangedEvent());
    return view;
  }

  public void delete(UUID id) {
    if (!providers.existsById(id)) {
      throw new OidcProviderNotFoundException(id);
    }
    providers.deleteById(id);
    events.publishEvent(new OidcProvidersChangedEvent());
  }

  private OidcProvider require(UUID id) {
    return providers.findById(id).orElseThrow(() -> new OidcProviderNotFoundException(id));
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static OidcProviderView toView(OidcProvider p) {
    String secret = p.getClientSecret();
    return new OidcProviderView(
        p.getId(),
        p.getName(),
        p.getProviderType().name(),
        p.isEnabled(),
        p.getClientId(),
        secret != null && !secret.isBlank(),
        p.getIssuerUri(),
        p.getScope(),
        p.getAuthorizationUri(),
        p.getTokenUri(),
        p.getUserInfoUri(),
        p.getJwkSetUri(),
        p.getUserNameAttribute(),
        p.getEmailAttribute(),
        p.getDisplayNameAttribute(),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }

  /** A partial update to an existing provider; {@code null} fields are left unchanged. */
  public record OidcProviderPatch(
      Boolean enabled,
      String name,
      String clientId,
      String clientSecret,
      String issuerUri,
      String scope,
      String authorizationUri,
      String tokenUri,
      String userInfoUri,
      String jwkSetUri,
      String userNameAttribute,
      String emailAttribute,
      String displayNameAttribute) {}

  /** The result of an issuer discovery probe. */
  public record OidcDiscoveryOutcome(
      boolean success,
      String authorizationUri,
      String tokenUri,
      String userInfoUri,
      String jwkSetUri,
      String error) {

    static OidcDiscoveryOutcome failure(String error) {
      return new OidcDiscoveryOutcome(false, null, null, null, null, error);
    }
  }
}
