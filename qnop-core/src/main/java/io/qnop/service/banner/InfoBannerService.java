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
package io.qnop.service.banner;

import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The two operator-authored banners (issue #664), assembled from application settings (ADR-0025).
 *
 * <p>Two placements, two audiences, and that is the whole reason they are separate settings rather
 * than one banner with a flag: the sign-in notice is readable by anyone who reaches the server (a
 * demo installation announcing its test credentials), while the in-app notice describes the state
 * of the deployment to the people signed in to it. Wiring both through one public response would
 * have made the second one public too.
 *
 * <p>Reads are free: {@link ApplicationSettingsService} serves an in-memory snapshot, so a client
 * may poll this without touching the database.
 */
@Service
public class InfoBannerService {

  private final ApplicationSettingsService settings;

  public InfoBannerService(ApplicationSettingsService settings) {
    this.settings = settings;
  }

  /** The notice on the sign-in and other authentication screens; empty when there is none. */
  public Optional<InfoBannerView> signIn() {
    return build(
        ApplicationSettingKey.BANNER_LOGIN_ENABLED,
        ApplicationSettingKey.BANNER_LOGIN_SEVERITY,
        ApplicationSettingKey.BANNER_LOGIN_TEXT,
        ApplicationSettingKey.BANNER_LOGIN_LINK_LABEL,
        ApplicationSettingKey.BANNER_LOGIN_LINK_URL);
  }

  /** The notice for signed-in users; empty when there is none. */
  public Optional<InfoBannerView> inApp() {
    return build(
        ApplicationSettingKey.BANNER_APP_ENABLED,
        ApplicationSettingKey.BANNER_APP_SEVERITY,
        ApplicationSettingKey.BANNER_APP_TEXT,
        ApplicationSettingKey.BANNER_APP_LINK_LABEL,
        ApplicationSettingKey.BANNER_APP_LINK_URL);
  }

  private Optional<InfoBannerView> build(
      ApplicationSettingKey enabled,
      ApplicationSettingKey severity,
      ApplicationSettingKey text,
      ApplicationSettingKey linkLabel,
      ApplicationSettingKey linkUrl) {
    if (!settings.getBoolean(enabled)) {
      return Optional.empty();
    }
    String message = settings.getString(text).strip();
    if (message.isEmpty()) {
      // Enabled but empty is how a banner looks half-configured. An empty bar
      // across every page says less than nothing, so it is simply not sent.
      return Optional.empty();
    }
    String label = settings.getString(linkLabel).strip();
    String url = settings.getString(linkUrl).strip();
    // Both or neither: a label with nowhere to go is broken, and a URL with no
    // label would have to invent one from the address.
    boolean hasLink = !label.isEmpty() && !url.isEmpty();
    return Optional.of(
        new InfoBannerView(
            settings.getString(severity), message, hasLink ? label : null, hasLink ? url : null));
  }
}
