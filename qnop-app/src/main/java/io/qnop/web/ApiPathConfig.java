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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Realizes the {@code /api/v1} URL versioning of the published REST contract (ADR-0015).
 *
 * <p>The OpenAPI spec declares {@code /api/v1} as the server root and keeps path items
 * version-relative (e.g. {@code /config}). Rather than baking the version into every generated
 * mapping, the prefix is applied here to every {@link RestController} only — leaving infrastructure
 * endpoints such as the actuator health probe ({@code /actuator/**}) unprefixed.
 */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

  /** The versioned API root every {@link RestController} handler is mounted under. */
  public static final String API_V1_PREFIX = "/api/v1";

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(
        API_V1_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
  }
}
