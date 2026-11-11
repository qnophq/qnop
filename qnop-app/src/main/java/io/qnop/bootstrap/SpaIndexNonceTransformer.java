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

import io.qnop.web.security.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

/**
 * Writes this request's CSP nonce into the SPA's root {@code index.html} (issue #913). The build
 * leaves {@value #PLACEHOLDER} wherever the nonce belongs — Vite's {@code html.cspNonce} puts it on
 * the {@code <meta property="csp-nonce">} the client reads and on the document's own {@code
 * <script>}/{@code <link>} tags — and this transformer swaps in {@link CspNonce#forRequest}, the
 * same value {@code PathAwareCspHeaderWriter} puts into the {@code style-src} of the response.
 *
 * <p>Only the bundle's root {@code index.html} is transformed, matched by location rather than by
 * file name; every other resource passes through untouched. The nonce is Base64 ({@code
 * [A-Za-z0-9+/=]}), so it needs no HTML escaping inside an attribute value.
 */
final class SpaIndexNonceTransformer implements ResourceTransformer {

  static final String INDEX = "index.html";

  static final String PLACEHOLDER = "__CSP_NONCE__";

  private final Resource rootIndex;

  SpaIndexNonceTransformer(Resource rootIndex) {
    this.rootIndex = rootIndex;
  }

  @Override
  public Resource transform(
      HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
      throws IOException {
    Resource transformed = chain.transform(request, resource);
    if (!isRootIndex(transformed)) {
      return transformed;
    }
    String template = transformed.getContentAsString(StandardCharsets.UTF_8);
    String html = template.replace(PLACEHOLDER, CspNonce.forRequest(request));
    return new TransformedResource(transformed, html.getBytes(StandardCharsets.UTF_8));
  }

  private boolean isRootIndex(Resource resource) throws IOException {
    if (!INDEX.equals(resource.getFilename()) || !rootIndex.exists()) {
      return false;
    }
    // Classpath bundles (the embedded release jar, the test fixture) compare by classpath path,
    // which
    // holds whichever class loader resolved either side; anything else by URI.
    if (resource instanceof ClassPathResource served
        && rootIndex instanceof ClassPathResource root) {
      return served.getPath().equals(root.getPath());
    }
    return resource.getURI().normalize().equals(rootIndex.getURI().normalize());
  }
}
