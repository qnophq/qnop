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
package io.qnop.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.BrandingSlot;
import io.qnop.repository.ApplicationAssetRepository;
import io.qnop.service.branding.BrandingService;
import io.qnop.service.branding.BrandingValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link BrandingService} against a real PostgreSQL (ADR-0020, ADR-0028): the validate →
 * sanitize → store pipeline, delete-then-insert replacement, and rejection of bad input. Requires
 * Docker.
 */
@Transactional
class BrandingServiceIT extends AbstractIntegrationTest {

  @Autowired BrandingService branding;
  @Autowired ApplicationAssetRepository assets;

  private static byte[] png(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void storesAndReadsPng() throws Exception {
    BrandingService.StoredAsset stored = branding.store("logo-light", png(16, 16), null);

    assertThat(stored.contentType()).isEqualTo("image/png");
    BrandingService.BrandingAsset asset = branding.get("logo-light").orElseThrow();
    assertThat(asset.contentType()).isEqualTo("image/png");
    assertThat(asset.sha256()).isEqualTo(stored.sha256());
  }

  @Test
  void sanitizesSvgBeforeStoring() {
    byte[] svg =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<script>alert(1)</script><path d=\"M0 0L10 10\"/></svg>")
            .getBytes(StandardCharsets.UTF_8);

    branding.store("logo-dark", svg, null);

    BrandingService.BrandingAsset asset = branding.get("logo-dark").orElseThrow();
    assertThat(asset.contentType()).isEqualTo("image/svg+xml");
    assertThat(new String(asset.content(), StandardCharsets.UTF_8)).doesNotContain("script");
  }

  @Test
  void replacesExistingSlot() throws Exception {
    branding.store("logo-light", png(16, 16), null);
    branding.store("logo-light", png(20, 20), null);

    long rows =
        assets.findAll().stream().filter(a -> a.getSlot() == BrandingSlot.LOGO_LIGHT).count();
    assertThat(rows).isEqualTo(1);
  }

  /** A PNG whose IHDR declares the given size, followed by no real pixel data (issue #906). */
  private static byte[] declaredPng(int width, int height) {
    ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height);
    ihdr.put((byte) 8).put((byte) 6);
    CRC32 crc = new CRC32();
    crc.update("IHDR".getBytes(StandardCharsets.US_ASCII));
    crc.update(ihdr.array());
    return ByteBuffer.allocate(8 + 4 + 4 + 13 + 4 + 16)
        .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
        .putInt(13)
        .put("IHDR".getBytes(StandardCharsets.US_ASCII))
        .put(ihdr.array())
        .putInt((int) crc.getValue())
        .array();
  }

  @Test
  void rejectsDeclaredDimensionsBeyondTheBudgetFromTheHeader() {
    assertThatThrownBy(() -> branding.store("logo-light", declaredPng(30_000, 30_000), null))
        .isInstanceOfSatisfying(
            BrandingValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    assertThatThrownBy(() -> branding.store("logo-light", declaredPng(2000, 2001), null))
        .isInstanceOf(BrandingValidationException.class);
  }

  @Test
  void rejectsWebpWithoutReadableDimensions() {
    byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    assertThatThrownBy(() -> branding.store("logo-light", webp, null))
        .isInstanceOfSatisfying(
            BrandingValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
  }

  @Test
  void rejectsUnsupportedType() {
    assertThatThrownBy(() -> branding.store("logomark", new byte[] {1, 2, 3, 4, 5}, null))
        .isInstanceOfSatisfying(
            BrandingValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(415));
  }

  @Test
  void rejectsOversizedAsset() {
    byte[] huge =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\">" + "a".repeat(600 * 1024) + "</svg>")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> branding.store("logo-light", huge, null))
        .isInstanceOfSatisfying(
            BrandingValidationException.class, e -> assertThat(e.getStatus()).isEqualTo(413));
  }

  @Test
  void deleteFallsBackToFactoryDefault() throws Exception {
    branding.store("logo-light", png(16, 16), null);
    assertThat(branding.get("logo-light").orElseThrow().contentType()).isEqualTo("image/png");

    branding.delete("logo-light");

    // The slot is not gone — it now serves the bundled factory default (SVG), never 404.
    assertThat(branding.get("logo-light").orElseThrow().contentType()).isEqualTo("image/svg+xml");
  }

  @Test
  void reportsDefaultThenCustomSource() throws Exception {
    assertThat(sourceOf("logomark")).isEqualTo(BrandingService.BrandingSource.DEFAULT);
    assertThat(branding.get("logomark").orElseThrow().contentType()).isEqualTo("image/svg+xml");

    branding.store("logomark", png(16, 16), null);

    assertThat(sourceOf("logomark")).isEqualTo(BrandingService.BrandingSource.CUSTOM);
  }

  private BrandingService.BrandingSource sourceOf(String slot) {
    return branding.statusAll().stream()
        .filter(s -> s.slot().equals(slot))
        .findFirst()
        .orElseThrow()
        .source();
  }
}
