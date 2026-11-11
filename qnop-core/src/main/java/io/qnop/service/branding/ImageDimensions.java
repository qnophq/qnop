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
package io.qnop.service.branding;

import io.qnop.service.image.ImageHeaderProbe;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * Best-effort intrinsic dimensions of a branding asset (issue #23): raster formats from their
 * header via {@link ImageHeaderProbe} (no pixel decode, issue #906), SVG via its {@code viewBox}
 * (or {@code width}/{@code height}). Returns empty when the dimensions cannot be determined (an
 * unreadable raster header, or an SVG without a viewBox). Upload validation rejects a raster with
 * unknown dimensions; an SVG without them relies on the byte-size cap.
 */
public record ImageDimensions(int width, int height) {

  private static final Logger log = LoggerFactory.getLogger(ImageDimensions.class);

  private static final Pattern VIEW_BOX =
      Pattern.compile("\\s*[-+0-9.eE]+\\s+[-+0-9.eE]+\\s+([-+0-9.eE]+)\\s+([-+0-9.eE]+)\\s*");
  private static final Pattern LENGTH = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

  public static Optional<ImageDimensions> read(String contentType, byte[] bytes) {
    return switch (contentType) {
      case BrandingLimits.PNG, BrandingLimits.WEBP -> readRaster(bytes);
      case BrandingLimits.SVG -> readSvg(bytes);
      default -> Optional.empty();
    };
  }

  /** Header-only: no pixels are decoded (issue #906). */
  private static Optional<ImageDimensions> readRaster(byte[] bytes) {
    return ImageHeaderProbe.probe(bytes).map(d -> new ImageDimensions(d.width(), d.height()));
  }

  private static Optional<ImageDimensions> readSvg(byte[] bytes) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setExpandEntityReferences(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Element svg =
          factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();

      String viewBox = svg.getAttribute("viewBox");
      Matcher viewBoxMatcher = VIEW_BOX.matcher(viewBox);
      if (!viewBox.isBlank() && viewBoxMatcher.matches()) {
        int w = (int) Math.ceil(Double.parseDouble(viewBoxMatcher.group(1)));
        int h = (int) Math.ceil(Double.parseDouble(viewBoxMatcher.group(2)));
        return Optional.of(new ImageDimensions(w, h));
      }
      Optional<Integer> width = length(svg.getAttribute("width"));
      Optional<Integer> height = length(svg.getAttribute("height"));
      if (width.isPresent() && height.isPresent()) {
        return Optional.of(new ImageDimensions(width.get(), height.get()));
      }
      return Optional.empty();
    } catch (Exception e) {
      // A catch-all over XML parsing of an uploaded file: "unreadable" is the
      // right answer to the caller, but silence is the wrong answer to whoever
      // later asks why a valid-looking logo had no dimensions (issue #659).
      log.debug("Could not read SVG dimensions from an uploaded asset", e);
      return Optional.empty();
    }
  }

  private static Optional<Integer> length(String value) {
    if (value == null) {
      return Optional.empty();
    }
    Matcher matcher = LENGTH.matcher(value);
    return matcher.find()
        ? Optional.of((int) Math.ceil(Double.parseDouble(matcher.group(1))))
        : Optional.empty();
  }
}
