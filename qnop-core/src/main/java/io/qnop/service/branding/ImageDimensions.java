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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Best-effort intrinsic dimensions of a branding asset (issue #23): raster formats via {@code
 * ImageIO}, SVG via its {@code viewBox} (or {@code width}/{@code height}). Returns empty when the
 * dimensions cannot be determined (e.g. WebP without an ImageIO plugin, or an SVG without a
 * viewBox) — the caller treats unknown dimensions as "skip the pixel-bound check" and relies on the
 * byte-size cap.
 */
public record ImageDimensions(int width, int height) {

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

  private static Optional<ImageDimensions> readRaster(byte[] bytes) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      return image == null
          ? Optional.empty()
          : Optional.of(new ImageDimensions(image.getWidth(), image.getHeight()));
    } catch (IOException e) {
      return Optional.empty();
    }
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
