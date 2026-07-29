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
package io.qnop.service.convert;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the out-of-process office converter (issue #639), overridable via {@code
 * qnop.office.*} or the matching {@code QNOP_OFFICE_*} environment variables.
 *
 * @param binary the executable to invoke; a bare name is resolved on {@code PATH} ({@code soffice})
 * @param timeout how long one conversion may take before the process is killed (60s). A cold
 *     LibreOffice needs a second or two; a minute means it has hung, and an export must not hold a
 *     request thread indefinitely because of it.
 */
@ConfigurationProperties(prefix = "qnop.office")
public record OfficeConverterProperties(String binary, Duration timeout) {

  public OfficeConverterProperties {
    binary = binary == null || binary.isBlank() ? "soffice" : binary.strip();
    timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
  }

  /** The documented defaults — for direct construction in tests and non-Spring callers. */
  public static OfficeConverterProperties defaults() {
    return new OfficeConverterProperties(null, null);
  }
}
