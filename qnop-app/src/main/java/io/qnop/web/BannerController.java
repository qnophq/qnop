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
package io.qnop.web;

import io.qnop.api.v1.endpoint.BannerApi;
import io.qnop.api.v1.model.BannerResponse;
import io.qnop.service.banner.InfoBannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The in-app announcement banner ({@code GET /api/v1/banner}, issue #664).
 *
 * <p>Its own endpoint rather than a field on the public {@code /config} response, for two reasons
 * that point the same way. It is authenticated: an operator saying "the extraction pipeline is
 * degraded" is talking to their users, not to the internet. And it is polled: a maintenance notice
 * has to arrive at a browser that has been open since this morning, which is a different cadence
 * from configuration a client reads once at startup.
 *
 * <p>Cheap enough to poll — the settings snapshot is in memory (ADR-0025), so this touches no
 * database at all.
 */
@RestController
public class BannerController implements BannerApi {

  private final InfoBannerService banners;

  public BannerController(InfoBannerService banners) {
    this.banners = banners;
  }

  @Override
  public ResponseEntity<BannerResponse> getBanner() {
    BannerResponse body = new BannerResponse();
    banners.inApp().map(BannerMapper::toApi).ifPresent(body::banner);
    return ResponseEntity.ok(body);
  }
}
