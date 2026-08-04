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
package io.qnop.service.notification;

import io.qnop.service.UserSettingKey;
import java.util.Locale;
import java.util.Optional;

/**
 * When a recipient wants review mail (issue #680).
 *
 * <p>Replaces a boolean that offered only "every event" or "nothing". Given that choice on a busy
 * review, the honest answer was nothing — and a notification nobody receives has failed however
 * correct it was.
 *
 * <p>Shared between the mail sink, which sends only for {@link #IMMEDIATE}, and the digest job,
 * which collects for {@link #DAILY}. Both read the same setting, so a recipient cannot end up
 * getting both or neither.
 */
public enum ReviewMailCadence {
  /** One mail per event, as it was before this existed. */
  IMMEDIATE,
  /** One summary per morning, in the recipient's own timezone. */
  DAILY,
  /** No review mail. In-app notifications are unaffected — they are not this setting's business. */
  OFF;

  /**
   * Reads a stored value, tolerating the booleans this setting held before.
   *
   * <p>The legacy mapping matches migration 0035 exactly: {@code false} was an explicit opt-out and
   * stays {@link #OFF}, while {@code true} becomes {@link #DAILY}. Being tolerant here means a
   * deployment whose migration has not run yet still behaves as intended rather than mailing
   * somebody who had said no.
   */
  public static Optional<ReviewMailCadence> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String value = raw.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "TRUE" -> Optional.of(DAILY);
      case "FALSE" -> Optional.of(OFF);
      case "IMMEDIATE" -> Optional.of(IMMEDIATE);
      case "DAILY" -> Optional.of(DAILY);
      case "OFF" -> Optional.of(OFF);
      default -> Optional.empty();
    };
  }

  /**
   * What an account that never chose gets — read from the registry rather than repeated here, so
   * the default has exactly one home (ADR-0025).
   */
  public static ReviewMailCadence registryDefault() {
    return parse(UserSettingKey.EMAIL_REVIEW_NOTIFICATIONS.getDefaultValue()).orElse(DAILY);
  }
}
