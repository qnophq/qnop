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
package io.qnop.bootstrap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.qnop.api.v1.model.Edition;
import io.qnop.web.RunningVersion;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Publishes what is running as {@code qnop.info{version, edition}} (issue #962), the usual "info
 * metric": its value is always 1 and the facts are in the tags. A dashboard joins on it to show the
 * version per instance and to mark the moment a deployment changed it.
 */
@Component
class BuildInfoMetrics implements MeterBinder {

  private final RunningVersion version;

  BuildInfoMetrics(RunningVersion version) {
    this.version = version;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("qnop.info", () -> 1)
        .tag("version", version.value())
        // The same constant GET /api/v1/config reports until an edition resolver exists.
        .tag("edition", Edition.COMMUNITY.getValue().toLowerCase(Locale.ROOT))
        .description("The running qnop build; always 1, the facts are in the tags")
        .register(registry);
  }
}
