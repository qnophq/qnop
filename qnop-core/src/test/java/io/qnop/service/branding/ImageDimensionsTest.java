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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Tests for {@link ImageDimensions} (issue #23). */
class ImageDimensionsTest {

  @Test
  void readsPngDimensionsViaImageIo() throws Exception {
    BufferedImage image = new BufferedImage(12, 34, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);

    Optional<ImageDimensions> dimensions =
        ImageDimensions.read(BrandingLimits.PNG, out.toByteArray());

    assertTrue(dimensions.isPresent());
    assertEquals(12, dimensions.get().width());
    assertEquals(34, dimensions.get().height());
  }

  @Test
  void readsSvgDimensionsFromViewBox() {
    byte[] svg =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\"></svg>")
            .getBytes(StandardCharsets.UTF_8);

    Optional<ImageDimensions> dimensions = ImageDimensions.read(BrandingLimits.SVG, svg);

    assertTrue(dimensions.isPresent());
    assertEquals(100, dimensions.get().width());
    assertEquals(50, dimensions.get().height());
  }

  @Test
  void returnsEmptyForUnreadableRaster() {
    assertTrue(ImageDimensions.read(BrandingLimits.PNG, new byte[] {1, 2, 3}).isEmpty());
  }
}
