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
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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
 *
 * <p>{@code index.html} is rendered per request (issue #913): {@link SpaIndexNonceTransformer}
 * writes this request's CSP nonce into it, so it is never cached ({@code no-store}) and carries no
 * validators a browser could revalidate against. Vite's content-hashed {@code /assets/**} never
 * change under their name and are cached as immutable.
 */
@Configuration
public class SpaWebConfiguration implements WebMvcConfigurer {

  private static final String STATIC_LOCATION = "classpath:/static/";

  private static final Duration HASHED_ASSET_MAX_AGE = Duration.ofDays(365);

  /**
   * Paths that never fall back to {@code index.html}: the API and actuator, the usage-tracking
   * proxy outside {@code /api} (ADR-0056), and the hashed assets — a missing chunk must be a 404,
   * not an HTML page the browser then refuses as a script.
   */
  private static final List<String> NO_FALLBACK_PREFIXES =
      List.of("api/", "actuator/", "t/", "assets/");

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations(STATIC_LOCATION + "assets/")
        .setCacheControl(CacheControl.maxAge(HASHED_ASSET_MAX_AGE).cachePublic().immutable());

    registry
        .addResourceHandler("/**")
        .addResourceLocations(STATIC_LOCATION)
        .setCacheControl(CacheControl.noStore())
        // index.html changes with every request, so Last-Modified would describe the template,
        // not the response.
        .setUseLastModified(false)
        // No resource caching: a cached transform would freeze one request's nonce, and a cached
        // resolution would grow with every distinct client-side route ever requested.
        .resourceChain(false)
        .addResolver(new SpaFallbackResolver())
        .addTransformer(new SpaIndexNonceTransformer());
  }

  /** Resolves real files, and every other non-API path to {@code index.html}. */
  private static final class SpaFallbackResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource requested = location.createRelative(resourcePath);
      if (requested.exists() && requested.isReadable()) {
        return requested;
      }
      if (NO_FALLBACK_PREFIXES.stream().anyMatch(resourcePath::startsWith)) {
        return null;
      }
      Resource index = location.createRelative(SpaIndexNonceTransformer.INDEX);
      return index.exists() && index.isReadable() ? index : null;
    }
  }
}
