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
    return riff(vp8Chunk(width, height));
  }

  /** A lossless (VP8L) WebP header declaring the given size (each side at most 16384). */
  public static byte[] webpVp8l(int width, int height) {
    return riff(vp8lChunk(width, height));
  }

  /**
   * An extended (VP8X) WebP declaring the given canvas, with one VP8L bitstream of the same size
   * (capped at the VP8L maximum of 16384 per side).
   */
  public static byte[] webpVp8x(int width, int height) {
    return webpVp8xWithFrame(width, height, Math.min(width, 16_384), Math.min(height, 16_384));
  }

  /** An extended (VP8X) WebP whose single VP8L bitstream declares its own size. */
  public static byte[] webpVp8xWithFrame(
      int canvasWidth, int canvasHeight, int frameWidth, int frameHeight) {
    return riff(vp8xChunk(canvasWidth, canvasHeight), vp8lChunk(frameWidth, frameHeight));
  }

  /** An animated WebP: a VP8X canvas and one ANMF frame (at 0,0) wrapping a VP8L bitstream. */
  public static byte[] webpAnimated(
      int canvasWidth, int canvasHeight, int frameWidth, int frameHeight) {
    byte[] bitstream = vp8lChunk(frameWidth, frameHeight);
    ByteBuffer anmf = le(16 + bitstream.length);
    put24(anmf, 0);
    put24(anmf, 0);
    put24(anmf, frameWidth - 1);
    put24(anmf, frameHeight - 1);
    put24(anmf, 100); // duration
    anmf.put((byte) 0);
    anmf.put(bitstream);
    return riff(vp8xChunk(canvasWidth, canvasHeight), chunk("ANMF", anmf.array()));
  }

  private static byte[] vp8Chunk(int width, int height) {
    ByteBuffer data = le(10);
    data.put((byte) 0x10).put((byte) 0x02).put((byte) 0x00); // frame tag: key frame
    data.put((byte) 0x9D).put((byte) 0x01).put((byte) 0x2A); // start code
    data.putShort((short) (width & 0x3FFF)).putShort((short) (height & 0x3FFF));
    return chunk("VP8 ", data.array());
  }

  private static byte[] vp8lChunk(int width, int height) {
    long bits = ((long) (width - 1) & 0x3FFF) | (((long) (height - 1) & 0x3FFF) << 14);
    ByteBuffer data = le(5);
    data.put((byte) 0x2F).putInt((int) bits);
    return chunk("VP8L", data.array());
  }

  private static byte[] vp8xChunk(int width, int height) {
    ByteBuffer data = le(10);
    data.putInt(0); // flags + reserved
    put24(data, width - 1);
    put24(data, height - 1);
    return chunk("VP8X", data.array());
  }

  private static void put24(ByteBuffer buffer, int value) {
    buffer.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** A RIFF chunk, padded to an even length. */
  private static byte[] chunk(String fourCc, byte[] payload) {
    ByteBuffer out = le(8 + payload.length + (payload.length & 1));
    out.put(fourCc.getBytes(StandardCharsets.US_ASCII));
    out.putInt(payload.length);
    out.put(payload);
    return out.array();
  }

  private static byte[] riff(byte[]... chunks) {
    int length = 0;
    for (byte[] c : chunks) {
      length += c.length;
    }
    ByteBuffer out = le(12 + length);
    out.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.putInt(4 + length);
    out.put("WEBP".getBytes(StandardCharsets.US_ASCII));
    for (byte[] c : chunks) {
      out.put(c);
    }
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
