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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an image's width and height from its header <em>without decoding any pixels</em> (issues
 * #906, #907), and decodes only images whose declared size fits a {@link PixelBudget}.
 *
 * <p>Every place that handles an untrusted raster — avatar and branding uploads, attachment
 * uploads, and the annotation exports that embed attachments — goes through this class. A
 * compressed image is small on disk but its decoded raster is {@code width × height × 4} bytes, so
 * the byte-size cap on an upload says nothing about the memory a decode will allocate. The header
 * is the only safe place to look before deciding to decode.
 *
 * <p>WebP is parsed here directly ({@link WebpHeader} walks every RIFF chunk) so its dimensions are
 * known whether or not an ImageIO WebP plugin is on the classpath. Every other format uses the
 * matching {@link ImageReader}'s {@code getWidth}/{@code getHeight}, which read only the header.
 */
public final class ImageHeaderProbe {

  private static final Logger log = LoggerFactory.getLogger(ImageHeaderProbe.class);

  private ImageHeaderProbe() {}

  /** Width and height in pixels, as declared by the image header. */
  public record Dimensions(int width, int height) {}

  /** The declared dimensions of {@code bytes}, or empty when the header cannot be read. */
  public static Optional<Dimensions> probe(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return Optional.empty();
    }
    return probe(new ByteArrayInputStream(bytes));
  }

  /**
   * The declared dimensions of the image in {@code in}, reading only headers. The caller owns (and
   * closes) the stream; its position afterwards is unspecified.
   */
  public static Optional<Dimensions> probe(InputStream in) {
    try {
      byte[] prefix = in.readNBytes(WebpHeader.RIFF_HEADER_LENGTH);
      if (prefix.length == 0) {
        return Optional.empty();
      }
      if (WebpHeader.matches(prefix)) {
        return WebpHeader.read(in);
      }
      InputStream whole = new SequenceInputStream(new ByteArrayInputStream(prefix), in);
      try (ImageInputStream stream = new MemoryCacheImageInputStream(whole)) {
        return readerDimensions(stream);
      }
    } catch (IOException | RuntimeException e) {
      // A malformed header is "unknown dimensions" to every caller; each decides
      // whether that rejects the upload or merely skips an embed.
      log.debug("Could not read image dimensions from the header", e);
      return Optional.empty();
    }
  }

  /**
   * Decodes the first image in {@code bytes} only if it fits {@code budget}; empty when the header
   * is unreadable, any declared size is over budget, or no reader can decode it.
   *
   * <p>Two checks precede the decode: this class's own header probe (which, for WebP, checks every
   * frame and bitstream) and the size the chosen {@link ImageReader} itself reports for the image
   * it is about to decode — so a reader that interprets the file differently from the probe is
   * still held to the budget.
   */
  public static Optional<BufferedImage> decodeWithinBudget(byte[] bytes, PixelBudget budget) {
    Optional<Dimensions> declared = probe(bytes);
    if (declared.isEmpty() || !budget.admits(declared.get())) {
      return Optional.empty();
    }
    try (ImageInputStream stream =
        new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return Optional.empty();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        if (!budget.admits(new Dimensions(reader.getWidth(0), reader.getHeight(0)))) {
          return Optional.empty();
        }
        return Optional.ofNullable(reader.read(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      log.debug("Could not decode an image within its pixel budget", e);
      return Optional.empty();
    }
  }

  private static Optional<Dimensions> readerDimensions(ImageInputStream stream) throws IOException {
    Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
    if (!readers.hasNext()) {
      return Optional.empty();
    }
    ImageReader reader = readers.next();
    try {
      reader.setInput(stream, true, true);
      return Optional.of(new Dimensions(reader.getWidth(0), reader.getHeight(0)));
    } finally {
      reader.dispose();
    }
  }
}
