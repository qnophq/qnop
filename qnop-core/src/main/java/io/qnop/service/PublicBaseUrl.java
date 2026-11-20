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
package io.qnop.service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The operator's {@code general.base_url}: the origin that links in mails and exports point to.
 *
 * <p>A missing base URL is a configuration fault, so it is logged at WARN, but once (issue #986).
 * Every mail and export that needs a link runs into it again, and a WARN per notification would
 * bury the faults that the WARN rate is meant to show. Later occurrences are logged at DEBUG. When
 * an admin changes the setting, the warning is re-armed, so clearing the base URL again is reported
 * again.
 */
@Component
public class PublicBaseUrl implements SettingsChangeListener {

  private static final Logger log = LoggerFactory.getLogger(PublicBaseUrl.class);

  private final ApplicationSettingsService settings;
  private final AtomicBoolean warned = new AtomicBoolean();

  // @Lazy breaks the construction cycle: ApplicationSettingsService injects every
  // SettingsChangeListener, this bean among them.
  public PublicBaseUrl(@Lazy ApplicationSettingsService settings) {
    this.settings = settings;
  }

  /** The configured base URL without surrounding blanks and trailing slashes, if it is set. */
  public Optional<String> configured() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    if (base == null || base.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(base.strip().replaceAll("/+$", ""));
  }

  /**
   * The configured base URL, or {@code ""} when it is unset, which is reported with {@link
   * #reportMissing}.
   *
   * @param consequence what goes wrong without it, such as "the digest will have no links"
   */
  public String orReportMissing(String consequence) {
    return configured()
        .orElseGet(
            () -> {
              reportMissing(consequence);
              return "";
            });
  }

  /**
   * Reports that a link could not be made absolute because the base URL is unset: at WARN the first
   * time, at DEBUG afterwards, until the setting changes.
   *
   * @param consequence what goes wrong without it, such as "the digest will have no links"
   */
  public void reportMissing(String consequence) {
    if (warned.compareAndSet(false, true)) {
      log.warn(
          "general.base_url is not configured — {}. Set Settings → General → Base URL;"
              + " further occurrences are logged at DEBUG.",
          consequence);
    } else {
      log.debug("general.base_url is not configured — {}", consequence);
    }
  }

  @Override
  public void onSettingsChanged(Set<ApplicationSettingKey> changedKeys) {
    if (changedKeys.contains(ApplicationSettingKey.GENERAL_BASE_URL)) {
      warned.set(false);
    }
  }
}
