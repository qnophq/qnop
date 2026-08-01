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

import io.qnop.api.v1.model.InfoBanner;
import io.qnop.service.banner.InfoBannerView;

/**
 * The one place a resolved banner becomes the published DTO (issue #664).
 *
 * <p>Shared by the two controllers that publish one — the sign-in banner rides along in {@code
 * /config}, the in-app banner has its own endpoint — so the two placements cannot drift into
 * answering in different shapes.
 */
final class BannerMapper {

  private BannerMapper() {}

  static InfoBanner toApi(InfoBannerView view) {
    return new InfoBanner()
        .severity(InfoBanner.SeverityEnum.fromValue(view.severity()))
        .message(view.message())
        .linkLabel(view.linkLabel())
        .linkUrl(view.linkUrl());
  }
}
