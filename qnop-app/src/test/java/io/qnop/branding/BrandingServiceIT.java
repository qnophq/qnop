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
import java.nio.charset.StandardCharsets;
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
  void deleteRemovesAsset() throws Exception {
    branding.store("logo-light", png(16, 16), null);

    branding.delete("logo-light");

    assertThat(branding.get("logo-light")).isEmpty();
  }
}
