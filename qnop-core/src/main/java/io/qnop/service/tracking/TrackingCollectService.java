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

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The collect endpoint's single entry point (issue #910): admits a measurement only in the shape
 * qnop's client builds ({@link TrackingMeasurementValidator}), then hands the rebuilt request to
 * {@link TrackingProxyService} for sanitising and forwarding.
 */
@Service
public class TrackingCollectService {

  private static final Logger log = LoggerFactory.getLogger(TrackingCollectService.class);

  private final TrackingConfigService config;
  private final TrackingProxyService proxy;

  public TrackingCollectService(TrackingConfigService config, TrackingProxyService proxy) {
    this.config = config;
    this.proxy = proxy;
  }

  /**
   * Validates and forwards one measurement.
   *
   * @return the backend's status code, or empty when nothing was forwarded (tracking off, or a
   *     request that is not a measurement qnop's client would send)
   */
  public Optional<Integer> collect(
      String method, String path, String query, byte[] body, String clientIp, String userAgent) {
    Optional<TrackingRuntimeConfig> active = config.current();
    if (active.isEmpty()) {
      return Optional.empty();
    }
    Optional<TrackingMeasurementValidator.Measurement> measurement =
        TrackingMeasurementValidator.validate(active.get(), method, path, query, body);
    if (measurement.isEmpty()) {
      // Not worth more than debug: a stale bundle, or somebody probing what else
      // this endpoint would pass on. Either way nothing travels.
      log.debug("Refused a {} {} that is not a measurement qnop sends", method, path);
      return Optional.empty();
    }
    TrackingMeasurementValidator.Measurement m = measurement.get();
    return proxy.forward(
        m.method(), path, m.query(), m.body(), m.contentType(), clientIp, userAgent);
  }
}
