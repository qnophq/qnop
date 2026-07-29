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
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a branding asset into a PNG that document formats can embed (issue #635 follow-up).
 *
 * <p>Word cannot embed SVG, and SVG is both the format of the bundled logos and the one operators
 * usually upload. WEBP is equally unusable — and the JDK cannot even decode it, which is why the
 * TwelveMonkeys ImageIO reader is on the runtime classpath. Without this class a logo would appear
 * everywhere in qnop except in the documents people actually send out.
 *
 * <p>Rasterizing is deliberately best-effort: it returns {@link Optional#empty()} rather than
 * throwing. A logo is decoration, and no export should fail because a branding asset could not be
 * converted.
 *
 * <p>Security note: the input is attacker-influenced (an operator upload). SVGs are already
 * sanitized on the way in ({@link SvgSanitizer}), and Batik is additionally pinned shut here —
 * external resources and scripts are refused, so a crafted document cannot make the server fetch a
 * URL or execute anything.
 */
@Component
public class BrandingRasterizer {

  private static final Logger log = LoggerFactory.getLogger(BrandingRasterizer.class);

  /**
   * Rendered width in pixels.
   *
   * <p>Generous on purpose: the logo is placed at roughly 100pt in a document, and print is 300 dpi
   * or better, so a rendition that merely matches the on-screen size would look soft on paper.
   */
  private static final int RENDER_WIDTH_PX = 600;

  /** A guard against a viewBox so extreme that the raster would exhaust memory. */
  private static final int MAX_RENDER_HEIGHT_PX = 2400;

  /**
   * A PNG rendition of the asset, or empty when it cannot be produced.
   *
   * @param contentType the stored asset's media type
   * @param bytes the stored asset
   */
  public Optional<byte[]> toPng(String contentType, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || contentType == null) {
      return Optional.empty();
    }
    try {
      if (BrandingLimits.PNG.equals(contentType)) {
        // Already embeddable; re-encoding would only lose quality and time.
        return Optional.of(bytes);
      }
      if (BrandingLimits.SVG.equals(contentType)) {
        return Optional.of(fromSvg(bytes));
      }
      return fromRaster(bytes);
    } catch (Exception e) {
      // Best-effort by contract: a logo that will not convert costs the document
      // its decoration, never the export.
      log.warn("Could not rasterize a {} branding asset for document exports", contentType, e);
      return Optional.empty();
    }
  }

  private byte[] fromSvg(byte[] svg) throws Exception {
    PNGTranscoder transcoder = new PNGTranscoder();
    // Stated rather than inherited: these are Batik's defaults today, and a
    // rasterizer fed operator uploads should not have its security posture
    // depend on a library default staying put across a Renovate bump.
    transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
    transcoder.addTranscodingHint(PNGTranscoder.KEY_EXECUTE_ONLOAD, false);
    transcoder.addTranscodingHint(PNGTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true);
    transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
    transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) RENDER_WIDTH_PX);
    // Both dimensions, not just the width: an SVG that carries only a viewBox has
    // no intrinsic size, and Batik would fall back to a square viewport and squash
    // the logo. ImageDimensions already knows how to read that viewBox.
    ImageDimensions.read(BrandingLimits.SVG, svg)
        .ifPresent(
            source -> {
              int height =
                  Math.min(
                      MAX_RENDER_HEIGHT_PX,
                      Math.max(
                          1,
                          Math.round(RENDER_WIDTH_PX * (float) source.height() / source.width())));
              transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);
            });

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transcoder.transcode(
        new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
    return out.toByteArray();
  }

  private Optional<byte[]> fromRaster(byte[] bytes) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
    if (image == null) {
      // No reader for this format — nothing to log loudly about, the upload
      // validation already accepted it for web serving.
      return Optional.empty();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    return ImageIO.write(image, "png", out) ? Optional.of(out.toByteArray()) : Optional.empty();
  }
}
