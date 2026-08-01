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
package io.qnop.service.tracking;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.http.OutboundUriGuard;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the tracking settings into something usable, or into nothing (issue #666).
 *
 * <p>Nothing is the common answer and the safe one. Tracking has to be switched on, name a backend,
 * carry a site id, and — for the self-hosted backends — point at a host that this deployment is
 * allowed to call. Any gap and the whole feature stays dark: no script tag, no endpoint, no
 * half-configured measurement quietly failing in the console.
 *
 * <p>Whether a private address may be called at all is a deployment property, not a setting. A
 * self-hosted Matomo usually lives on one, and that is legitimate — but "an admin can point this
 * server at 169.254.169.254 from the web UI" is not, so the decision sits where only an operator
 * with deployment access can make it.
 */
@Service
public class TrackingConfigService {

  private static final Logger log = LoggerFactory.getLogger(TrackingConfigService.class);

  private final ApplicationSettingsService settings;
  private final boolean allowPrivateHost;

  public TrackingConfigService(
      ApplicationSettingsService settings,
      @Value("${qnop.tracking.allow-private-host:false}") boolean allowPrivateHost) {
    this.settings = settings;
    this.allowPrivateHost = allowPrivateHost;
  }

  /** The active configuration, or empty when tracking is off or incompletely configured. */
  public Optional<TrackingRuntimeConfig> current() {
    if (!settings.getBoolean(ApplicationSettingKey.TRACKING_ENABLED)) {
      return Optional.empty();
    }
    Optional<TrackingProvider> provider =
        TrackingProvider.fromId(settings.getString(ApplicationSettingKey.TRACKING_PROVIDER));
    if (provider.isEmpty()) {
      return Optional.empty();
    }
    TrackingProvider backend = provider.get();

    String siteId = settings.getString(ApplicationSettingKey.TRACKING_SITE_ID).strip();
    if (siteId.isEmpty()) {
      log.warn("Usage tracking is enabled for {} but no site id is configured", backend.id());
      return Optional.empty();
    }

    String host = settings.getString(ApplicationSettingKey.TRACKING_HOST).strip();
    if (host.isEmpty() && backend.requiresHost()) {
      log.warn(
          "Usage tracking is enabled for {} but it is self-hosted and has no host", backend.id());
      return Optional.empty();
    }
    String collectBase = trimTrailingSlash(host.isEmpty() ? backend.defaultHost() : host);
    String scriptBase = trimTrailingSlash(host.isEmpty() ? backend.defaultScriptHost() : host);

    try {
      // Checked here rather than only on save: the setting may predate a change to
      // this deployment's policy, and the request path must never be the first
      // place that question is asked.
      OutboundUriGuard.requireAllowedHttpUri(collectBase, "tracking.host", true, allowPrivateHost);
      OutboundUriGuard.requireAllowedHttpUri(scriptBase, "tracking.host", true, allowPrivateHost);
    } catch (IllegalArgumentException e) {
      log.warn("Usage tracking is disabled: {}", e.getMessage());
      return Optional.empty();
    }

    return Optional.of(
        new TrackingRuntimeConfig(
            backend,
            siteId,
            scriptBase + backend.scriptPath(),
            collectBase,
            settings.getBoolean(ApplicationSettingKey.TRACKING_CONSENT_REQUIRED),
            settings.getBoolean(ApplicationSettingKey.TRACKING_RESPECT_DNT),
            settings.getBoolean(ApplicationSettingKey.TRACKING_PRIVILEGED_ROLES),
            "anonymized"
                .equals(settings.getString(ApplicationSettingKey.TRACKING_FORWARD_CLIENT_IP))));
  }

  private static String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
