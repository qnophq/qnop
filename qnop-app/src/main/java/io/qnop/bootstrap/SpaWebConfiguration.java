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

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the embedded qnop-ui SPA (ADR-0040). Release builds embed the built bundle under {@code
 * classpath:/static/}; every client-side route (e.g. {@code /reviews/<slug>}) falls back to {@code
 * index.html} so a browser reload deep inside the app works. The fallback NEVER applies to {@code
 * /api/**} or {@code /actuator/**} — controllers outrank this resource handler anyway, and unknown
 * API paths must stay honest 404s for API clients. Without an embedded bundle (developer builds)
 * the handler simply finds no resources and requests 404 as before.
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                  return requested;
                }
                if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                  return null;
                }
                Resource index = location.createRelative("index.html");
                return index.exists() && index.isReadable() ? index : null;
              }
            });
  }
}
