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
 * <p>WebP is parsed here directly (VP8, VP8L and VP8X headers) so its dimensions are known whether
 * or not an ImageIO WebP plugin is on the classpath. Every other format uses the matching {@link
 * ImageReader}'s {@code getWidth}/{@code getHeight}, which read only the header.
 */
public final class ImageHeaderProbe {

  private static final Logger log = LoggerFactory.getLogger(ImageHeaderProbe.class);

  /** Enough bytes for the RIFF header plus the first chunk's dimension fields. */
  private static final int WEBP_HEADER_LENGTH = 30;

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
   * The declared dimensions of the image in {@code in}, reading only as far as the header. The
   * caller owns (and closes) the stream; its position afterwards is unspecified.
   */
  public static Optional<Dimensions> probe(InputStream in) {
    try {
      byte[] prefix = in.readNBytes(WEBP_HEADER_LENGTH);
      if (prefix.length == 0) {
        return Optional.empty();
      }
      if (isWebp(prefix)) {
        return webpDimensions(prefix);
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
   * Decodes {@code bytes} only if its header-declared size fits {@code budget}; empty when the
   * header is unreadable, the size is over budget, or no reader can decode it.
   */
  public static Optional<BufferedImage> decodeWithinBudget(byte[] bytes, PixelBudget budget) {
    Optional<Dimensions> dimensions = probe(bytes);
    if (dimensions.isEmpty() || !budget.admits(dimensions.get())) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
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

  private static boolean isWebp(byte[] b) {
    return b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  /** Dimensions from the first chunk of a WebP file: VP8 (lossy), VP8L (lossless) or VP8X. */
  private static Optional<Dimensions> webpDimensions(byte[] b) {
    if (b.length < 20) {
      return Optional.empty();
    }
    String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
    int data = 20;
    switch (chunk) {
      case "VP8 " -> {
        if (b.length < data + 10
            || (b[data + 3] & 0xFF) != 0x9D
            || (b[data + 4] & 0xFF) != 0x01
            || (b[data + 5] & 0xFF) != 0x2A) {
          return Optional.empty();
        }
        return Optional.of(new Dimensions(le16(b, data + 6) & 0x3FFF, le16(b, data + 8) & 0x3FFF));
      }
      case "VP8L" -> {
        if (b.length < data + 5 || (b[data] & 0xFF) != 0x2F) {
          return Optional.empty();
        }
        long bits =
            (b[data + 1] & 0xFFL)
                | (b[data + 2] & 0xFFL) << 8
                | (b[data + 3] & 0xFFL) << 16
                | (b[data + 4] & 0xFFL) << 24;
        int width = (int) (bits & 0x3FFF) + 1;
        int height = (int) ((bits >> 14) & 0x3FFF) + 1;
        return Optional.of(new Dimensions(width, height));
      }
      case "VP8X" -> {
        if (b.length < data + 10) {
          return Optional.empty();
        }
        return Optional.of(new Dimensions(le24(b, data + 4) + 1, le24(b, data + 7) + 1));
      }
      default -> {
        return Optional.empty();
      }
    }
  }

  private static int le16(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8;
  }

  private static int le24(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF) << 16;
  }
}
