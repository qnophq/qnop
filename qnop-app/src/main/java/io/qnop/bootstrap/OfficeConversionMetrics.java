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
import io.qnop.service.convert.ThrottledOfficeConverter;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Publishes how busy the office converter is (issue #651): {@code
 * qnop.office.conversions{state="active"}} against {@code state="waiting"}, plus the ceiling as
 * {@code qnop.office.conversion.limit}.
 *
 * <p>Without these, an instance running permanently at its limit is invisible until it starts
 * refusing exports — the queue does its job silently right up to the moment it cannot. Waiting
 * above zero for any length of time is the signal that the limit wants raising (or the instance
 * more memory); active pinned at the limit says the same thing louder.
 */
@Component
class OfficeConversionMetrics implements MeterBinder {

  private final ThrottledOfficeConverter converter;

  OfficeConversionMetrics(ThrottledOfficeConverter converter) {
    this.converter = converter;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    gauge(registry, "active", ThrottledOfficeConverter::active);
    gauge(registry, "waiting", ThrottledOfficeConverter::waiting);
    Gauge.builder("qnop.office.conversion.limit", converter, c -> c.limit())
        .description("How many conversions this instance allows at once")
        .register(registry);
  }

  private void gauge(
      MeterRegistry registry, String state, ToDoubleFunction<ThrottledOfficeConverter> value) {
    Gauge.builder("qnop.office.conversions", converter, value)
        .tag("state", state)
        .description("Out-of-process document conversions, running and queued")
        .register(registry);
  }
}
