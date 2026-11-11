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
package io.qnop.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.image.ImageHeaderProbe.Dimensions;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Header-only dimension probing and the decode budget (issues #906, #907). */
class ImageHeaderProbeTest {

  private static final PixelBudget SMALL = new PixelBudget(1024, 1024L * 1024L);

  @Test
  void readsDimensionsOfRealRasterFormats() {
    assertThat(ImageHeaderProbe.probe(ImageFixtures.realPng(12, 34)))
        .contains(new Dimensions(12, 34));
    assertThat(ImageHeaderProbe.probe(ImageFixtures.realJpeg(40, 30)))
        .contains(new Dimensions(40, 30));
    assertThat(ImageHeaderProbe.probe(ImageFixtures.realGif(7, 9))).contains(new Dimensions(7, 9));
  }

  @Test
  void readsDeclaredPngDimensionsWithoutDecodingPixels() {
    // 20000 x 20000 RGBA would need ~1.6 GB if decoded; the probe only reads IHDR.
    assertThat(ImageHeaderProbe.probe(ImageFixtures.pngHeader(20_000, 20_000)))
        .contains(new Dimensions(20_000, 20_000));
  }

  @Test
  void readsAllThreeWebpHeaderVariants() {
    assertThat(ImageHeaderProbe.probe(ImageFixtures.webpVp8(16_000, 15_000)))
        .contains(new Dimensions(16_000, 15_000));
    assertThat(ImageHeaderProbe.probe(ImageFixtures.webpVp8l(16_384, 3)))
        .contains(new Dimensions(16_384, 3));
    assertThat(ImageHeaderProbe.probe(ImageFixtures.webpVp8x(1_000_000, 2)))
        .contains(new Dimensions(1_000_000, 2));
  }

  @Test
  void probesFromAStream() {
    assertThat(
            ImageHeaderProbe.probe(
                new ByteArrayInputStream(ImageFixtures.pngHeader(20_000, 20_000))))
        .contains(new Dimensions(20_000, 20_000));
    assertThat(ImageHeaderProbe.probe(new ByteArrayInputStream(ImageFixtures.webpVp8x(5, 6))))
        .contains(new Dimensions(5, 6));
    assertThat(ImageHeaderProbe.probe(new ByteArrayInputStream(ImageFixtures.realJpeg(8, 4))))
        .contains(new Dimensions(8, 4));
  }

  @Test
  void unreadableInputYieldsEmpty() {
    assertThat(ImageHeaderProbe.probe(new byte[] {1, 2, 3})).isEmpty();
    assertThat(ImageHeaderProbe.probe(new byte[0])).isEmpty();
    byte[] truncatedWebp = Arrays.copyOf(ImageFixtures.webpVp8(10, 10), 22);
    assertThat(ImageHeaderProbe.probe(truncatedWebp)).isEmpty();
  }

  @Test
  void budgetBoundsEachSideAndTheTotal() {
    assertThat(SMALL.admits(new Dimensions(1024, 1024))).isTrue();
    assertThat(SMALL.admits(new Dimensions(1025, 1))).isFalse();
    assertThat(SMALL.admits(new Dimensions(1, 1025))).isFalse();
    PixelBudget wide = new PixelBudget(10_000, 1_000_000);
    assertThat(wide.admits(new Dimensions(10_000, 100))).isTrue();
    assertThat(wide.admits(new Dimensions(10_000, 101))).isFalse();
    assertThat(wide.admits(new Dimensions(0, 10))).isFalse();
  }

  @Test
  void decodeWithinBudgetDecodesAdmittedImages() {
    assertThat(ImageHeaderProbe.decodeWithinBudget(ImageFixtures.realPng(10, 20), SMALL))
        .hasValueSatisfying(
            image -> {
              assertThat(image.getWidth()).isEqualTo(10);
              assertThat(image.getHeight()).isEqualTo(20);
            });
  }

  @Test
  void decodeWithinBudgetRefusesOversizedDeclarationsBeforeDecoding() {
    assertThat(ImageHeaderProbe.decodeWithinBudget(ImageFixtures.pngHeader(20_000, 20_000), SMALL))
        .isEmpty();
    assertThat(ImageHeaderProbe.decodeWithinBudget(ImageFixtures.webpVp8x(5_000, 5_000), SMALL))
        .isEmpty();
  }
}
