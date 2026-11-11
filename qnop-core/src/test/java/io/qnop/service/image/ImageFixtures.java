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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Synthetic image headers for the dimension-budget tests (issues #906/#907): tiny byte arrays whose
 * headers <em>declare</em> arbitrary dimensions, so a test can prove a size check happens before
 * any pixel buffer is allocated.
 */
public final class ImageFixtures {

  private ImageFixtures() {}

  /** A real, decodable PNG of the given size. */
  public static byte[] realPng(int width, int height) {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
  }

  /** A real, decodable JPEG of the given size. */
  public static byte[] realJpeg(int width, int height) {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
  }

  /** A real, decodable GIF of the given size. */
  public static byte[] realGif(int width, int height) {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED), "gif");
  }

  /** A PNG signature plus an IHDR declaring the given size, a tiny IDAT and IEND. */
  public static byte[] pngHeader(int width, int height) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    ByteBuffer ihdr = ByteBuffer.allocate(13);
    ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0);
    ihdr.put((byte) 0).put((byte) 0);
    chunk(out, "IHDR", ihdr.array());
    chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
    chunk(out, "IEND", new byte[0]);
    return out.toByteArray();
  }

  /** A lossy (VP8) WebP header declaring the given size (each side at most 16383). */
  public static byte[] webpVp8(int width, int height) {
    ByteBuffer data = le(10);
    data.put((byte) 0x10).put((byte) 0x02).put((byte) 0x00); // frame tag: key frame
    data.put((byte) 0x9D).put((byte) 0x01).put((byte) 0x2A); // start code
    data.putShort((short) (width & 0x3FFF)).putShort((short) (height & 0x3FFF));
    return riff("VP8 ", data.array());
  }

  /** A lossless (VP8L) WebP header declaring the given size (each side at most 16384). */
  public static byte[] webpVp8l(int width, int height) {
    long bits = ((long) (width - 1) & 0x3FFF) | (((long) (height - 1) & 0x3FFF) << 14);
    ByteBuffer data = le(5);
    data.put((byte) 0x2F).putInt((int) bits);
    return riff("VP8L", data.array());
  }

  /** An extended (VP8X) WebP header declaring the given canvas size (24-bit sides). */
  public static byte[] webpVp8x(int width, int height) {
    ByteBuffer data = le(10);
    data.putInt(0); // flags + reserved
    put24(data, width - 1);
    put24(data, height - 1);
    return riff("VP8X", data.array());
  }

  private static void put24(ByteBuffer buffer, int value) {
    buffer.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] riff(String fourCc, byte[] payload) {
    ByteBuffer out = le(20 + payload.length);
    out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.putInt(12 + payload.length);
    out.put("WEBP".getBytes(StandardCharsets.US_ASCII));
    out.put(fourCc.getBytes(StandardCharsets.US_ASCII));
    out.putInt(payload.length);
    out.put(payload);
    return out.array();
  }

  private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
    byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
    out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
    out.writeBytes(typeBytes);
    out.writeBytes(data);
    CRC32 crc = new CRC32();
    crc.update(typeBytes);
    crc.update(data);
    out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
  }

  private static byte[] encode(BufferedImage image, String format) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(image, format, out)) {
        throw new IllegalStateException("no writer for " + format);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
