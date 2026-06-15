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
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Spring Security {@link ClientRegistrationRepository} backed by the {@code oidc_provider} table
 * (issue #21). One {@link ClientRegistration} is built per {@code enabled} row, keyed by the
 * provider's UUID ({@link OidcRegistrationIds}). The cache is rebuilt on demand via {@link
 * #refresh()} (called by {@code OidcProviderService} after any change), so providers come and go
 * without a restart. Build failures (e.g. an unreachable issuer for discovery) are logged and the
 * provider is skipped rather than failing the whole refresh.
 */
@Component
public class DbClientRegistrationRepository
    implements ClientRegistrationRepository, Iterable<ClientRegistration> {

  private static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";
  private static final String GOOGLE_ISSUER = "https://accounts.google.com";
  private static final Logger log = LoggerFactory.getLogger(DbClientRegistrationRepository.class);

  private final io.qnop.repository.OidcProviderRepository providers;
  private final AtomicReference<Map<String, ClientRegistration>> cache =
      new AtomicReference<>(Map.of());

  public DbClientRegistrationRepository(io.qnop.repository.OidcProviderRepository providers) {
    this.providers = providers;
  }

  @PostConstruct
  public void init() {
    refresh();
  }

  /** Rebuilds the cache once a provider change has committed (issue #21). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProvidersChanged(OidcProvidersChangedEvent event) {
    refresh();
  }

  @Override
  public ClientRegistration findByRegistrationId(String registrationId) {
    return cache.get().get(registrationId);
  }

  @Override
  public java.util.Iterator<ClientRegistration> iterator() {
    return cache.get().values().iterator();
  }

  /** Rebuilds the registration cache from the enabled provider rows. */
  @Transactional(readOnly = true)
  public void refresh() {
    Map<String, ClientRegistration> built = new LinkedHashMap<>();
    for (OidcProvider provider : providers.findAll()) {
      if (!provider.isEnabled()) {
        continue;
      }
      String registrationId = OidcRegistrationIds.of(provider.getId());
      try {
        built.put(registrationId, buildRegistration(provider, registrationId));
      } catch (RuntimeException e) {
        log.warn(
            "Skipping OIDC provider {} (registrationId={}): {}",
            provider.getName(),
            registrationId,
            e.getMessage());
      }
    }
    cache.set(Map.copyOf(built));
  }

  private ClientRegistration buildRegistration(OidcProvider provider, String registrationId) {
    ClientRegistration.Builder builder =
        switch (provider.getProviderType()) {
          case OIDC ->
              ClientRegistrations.fromIssuerLocation(requireIssuer(provider))
                  .registrationId(registrationId);
          case GOOGLE ->
              ClientRegistrations.fromIssuerLocation(GOOGLE_ISSUER).registrationId(registrationId);
          case GITHUB ->
              ClientRegistration.withRegistrationId(registrationId)
                  .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                  .authorizationUri("https://github.com/login/oauth/authorize")
                  .tokenUri("https://github.com/login/oauth/access_token")
                  .userInfoUri("https://api.github.com/user")
                  .userNameAttributeName("id");
          case FACEBOOK ->
              ClientRegistration.withRegistrationId(registrationId)
                  .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                  .authorizationUri("https://www.facebook.com/v18.0/dialog/oauth")
                  .tokenUri("https://graph.facebook.com/v18.0/oauth/access_token")
                  .userInfoUri("https://graph.facebook.com/me?fields=id,name,email")
                  .userNameAttributeName("id");
          case OAUTH2 ->
              ClientRegistration.withRegistrationId(registrationId)
                  .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                  .authorizationUri(require(provider.getAuthorizationUri(), "authorizationUri"))
                  .tokenUri(require(provider.getTokenUri(), "tokenUri"))
                  .userInfoUri(require(provider.getUserInfoUri(), "userInfoUri"))
                  .jwkSetUri(provider.getJwkSetUri())
                  .userNameAttributeName(orDefault(provider.getUserNameAttribute(), "sub"));
        };
    return builder
        .clientId(provider.getClientId())
        .clientSecret(provider.getClientSecret())
        .redirectUri(REDIRECT_URI)
        .scope(scopes(provider))
        .build();
  }

  private static List<String> scopes(OidcProvider provider) {
    return switch (provider.getProviderType()) {
      case GITHUB -> List.of("read:user", "user:email");
      case FACEBOOK -> List.of("public_profile", "email");
      default -> {
        String configured = provider.getScope();
        if (configured == null || configured.isBlank()) {
          yield List.of("openid", "email", "profile");
        }
        List<String> parsed = new ArrayList<>(Arrays.asList(configured.trim().split("\\s+")));
        yield parsed;
      }
    };
  }

  private static String requireIssuer(OidcProvider provider) {
    return require(provider.getIssuerUri(), "issuerUri");
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
