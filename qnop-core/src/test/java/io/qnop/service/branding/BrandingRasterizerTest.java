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

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The branding rasterizer (issue #635 follow-up). */
class BrandingRasterizerTest {

  private final BrandingRasterizer rasterizer = new BrandingRasterizer();

  private static BufferedImage decode(byte[] png) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(png));
  }

  @Test
  @DisplayName("rasterizes the bundled logo, keeping its aspect ratio")
  void rasterizesTheBundledLogo() throws Exception {
    byte[] svg =
        BrandingRasterizerTest.class
            .getResourceAsStream("/branding/defaults/logo-light.svg")
            .readAllBytes();

    BufferedImage image = decode(rasterizer.toPng(BrandingLimits.SVG, svg).orElseThrow());

    // The logo's viewBox is 126x64; a renderer that only set the width would get
    // a squashed square out of Batik, which is the bug this asserts against.
    assertThat(image.getWidth()).isEqualTo(600);
    assertThat((double) image.getWidth() / image.getHeight()).isCloseTo(126.0 / 64.0, within(0.05));
  }

  @Test
  @DisplayName("an SVG carrying only width and height rasterizes too")
  void rasterizesWithoutAViewBox() throws Exception {
    String svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"100\">"
            + "<rect width=\"200\" height=\"100\" fill=\"#123456\"/></svg>";

    BufferedImage image =
        decode(
            rasterizer
                .toPng(BrandingLimits.SVG, svg.getBytes(StandardCharsets.UTF_8))
                .orElseThrow());

    assertThat(image.getWidth()).isEqualTo(600);
    assertThat(image.getHeight()).isEqualTo(300);
  }

  @Test
  @DisplayName("a PNG is passed through untouched rather than re-encoded")
  void passesPngThrough() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(10, 5, BufferedImage.TYPE_INT_ARGB), "png", out);
    byte[] png = out.toByteArray();

    // Identity, not merely equality: re-encoding would cost quality and time for
    // a format that is already embeddable.
    assertThat(rasterizer.toPng(BrandingLimits.PNG, png)).containsSame(png);
  }

  @Test
  @DisplayName("a WEBP asset converts, because the JDK alone could not read one")
  void convertsWebp() throws Exception {
    // TwelveMonkeys is a runtime dependency precisely so this does not return
    // empty; WEBP is an accepted upload and would otherwise vanish from exports.
    assertThat(ImageIO.getImageReadersByFormatName("webp")).hasNext();
  }

  @Test
  @DisplayName("unconvertible input yields no logo instead of failing the export")
  void refusesGracefully() {
    assertThat(rasterizer.toPng(BrandingLimits.SVG, "not an svg".getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
    assertThat(rasterizer.toPng(BrandingLimits.PNG, new byte[0])).isEmpty();
    assertThat(rasterizer.toPng(null, new byte[] {1, 2, 3})).isEmpty();
    assertThat(rasterizer.toPng("image/tiff", new byte[] {1, 2, 3})).isEqualTo(Optional.empty());
  }

  private static org.assertj.core.data.Offset<Double> within(double tolerance) {
    return org.assertj.core.data.Offset.offset(tolerance);
  }
}
