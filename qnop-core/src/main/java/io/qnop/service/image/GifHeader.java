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

import io.qnop.service.image.ImageHeaderProbe.Dimensions;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Dimensions of a GIF from its block structure, without decoding (issues #906, #907).
 *
 * <p>An ImageIO GIF reader reports the first frame's size, but every consumer that renders a GIF —
 * browsers, office suites — sizes its canvas from the logical screen and decodes every frame. So
 * the logical screen and every image descriptor are read, and the result is the larger of the
 * screen and the furthest extent any frame reaches. The LZW data and extension payloads are skipped
 * sub-block by sub-block, never buffered.
 *
 * <p>A file with more than {@link #MAX_FRAMES} frames, a zero-sized screen or frame, or no frame at
 * all is unreadable (empty). A file that ends early after at least one complete frame descriptor is
 * still measured: browsers render truncated GIFs, so their size still matters.
 */
final class GifHeader {

  /** Frames beyond this make the file unreadable: each one is a full decode for a renderer. */
  static final int MAX_FRAMES = 1_000;

  private static final int IMAGE_DESCRIPTOR = 0x2C;
  private static final int EXTENSION = 0x21;
  private static final int TRAILER = 0x3B;

  private GifHeader() {}

  /** True when {@code prefix} starts with a {@code GIF87a}/{@code GIF89a} signature. */
  static boolean matches(byte[] prefix) {
    return prefix.length >= 6
        && prefix[0] == 'G'
        && prefix[1] == 'I'
        && prefix[2] == 'F'
        && prefix[3] == '8'
        && (prefix[4] == '7' || prefix[4] == '9')
        && prefix[5] == 'a';
  }

  /** Reads the whole block structure from {@code in}, which starts at the signature. */
  static Optional<Dimensions> read(InputStream in) throws IOException {
    byte[] header = readExactly(in, 13);
    int screenWidth = le16(header, 6);
    int screenHeight = le16(header, 8);
    if (screenWidth == 0 || screenHeight == 0) {
      return Optional.empty();
    }
    skipColorTable(in, header[10]);

    int width = screenWidth;
    int height = screenHeight;
    int frames = 0;
    try {
      int block;
      while ((block = in.read()) != -1 && block != TRAILER) {
        if (block == EXTENSION) {
          readExactly(in, 1); // label
          skipSubBlocks(in);
        } else if (block == IMAGE_DESCRIPTOR) {
          byte[] descriptor = readExactly(in, 9);
          int frameWidth = le16(descriptor, 4);
          int frameHeight = le16(descriptor, 6);
          if (frameWidth == 0 || frameHeight == 0 || ++frames > MAX_FRAMES) {
            return Optional.empty();
          }
          width = Math.max(width, le16(descriptor, 0) + frameWidth);
          height = Math.max(height, le16(descriptor, 2) + frameHeight);
          skipColorTable(in, descriptor[8]);
          readExactly(in, 1); // LZW minimum code size
          skipSubBlocks(in);
        } else {
          return Optional.empty();
        }
      }
    } catch (EOFException e) {
      if (frames == 0) {
        throw e;
      }
    }
    return frames == 0 ? Optional.empty() : Optional.of(new Dimensions(width, height));
  }

  private static void skipColorTable(InputStream in, byte packed) throws IOException {
    if ((packed & 0x80) != 0) {
      in.skipNBytes(3L * (1 << ((packed & 0x07) + 1)));
    }
  }

  private static void skipSubBlocks(InputStream in) throws IOException {
    int length;
    while ((length = in.read()) > 0) {
      in.skipNBytes(length);
    }
    if (length == -1) {
      throw new EOFException("truncated GIF sub-blocks");
    }
  }

  private static byte[] readExactly(InputStream in, int length) throws IOException {
    byte[] data = in.readNBytes(length);
    if (data.length != length) {
      throw new EOFException("truncated GIF block");
    }
    return data;
  }

  private static int le16(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8;
  }
}
