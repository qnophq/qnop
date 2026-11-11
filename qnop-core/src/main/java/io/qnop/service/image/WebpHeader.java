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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Dimensions of a WebP file from its RIFF chunks, without decoding (issues #906, #907).
 *
 * <p>The extended format's VP8X canvas size is not authoritative on its own: every bitstream
 * ({@code VP8 }/{@code VP8L}) and every animation frame ({@code ANMF}) carries its own size, and a
 * decoder allocates for those. So every chunk is walked and each declared size is checked; the file
 * is reported unreadable (empty) when any frame or bitstream is larger than the canvas, when a
 * simple-format file carries more than one bitstream, or when no bitstream exists at all.
 *
 * <p>The walk streams: chunk payloads that carry no dimensions are skipped, never buffered, so the
 * memory cost is constant for any file size. {@code ANMF} payloads are entered rather than skipped,
 * because their frame data is itself a sequence of chunks.
 */
final class WebpHeader {

  /** {@code RIFF <size> WEBP}. */
  static final int RIFF_HEADER_LENGTH = 12;

  private static final int CHUNK_HEADER_LENGTH = 8;
  private static final int ANMF_HEADER_LENGTH = 16;
  private static final int VP8X_LENGTH = 10;
  private static final int VP8_HEADER_LENGTH = 10;
  private static final int VP8L_HEADER_LENGTH = 5;

  private WebpHeader() {}

  /** True when {@code prefix} starts with a RIFF/WEBP header. */
  static boolean matches(byte[] prefix) {
    return prefix.length >= RIFF_HEADER_LENGTH
        && prefix[0] == 'R'
        && prefix[1] == 'I'
        && prefix[2] == 'F'
        && prefix[3] == 'F'
        && prefix[8] == 'W'
        && prefix[9] == 'E'
        && prefix[10] == 'B'
        && prefix[11] == 'P';
  }

  /**
   * Walks the chunks that follow the 12-byte RIFF header (already consumed from {@code in}).
   *
   * @return the canvas size (VP8X) or the single bitstream's size, or empty when inconsistent
   */
  static Optional<Dimensions> read(InputStream in) throws IOException {
    Dimensions canvas = null;
    Dimensions single = null;
    int bitstreams = 0;
    byte[] header;
    while ((header = in.readNBytes(CHUNK_HEADER_LENGTH)).length == CHUNK_HEADER_LENGTH) {
      String fourCc = new String(header, 0, 4, StandardCharsets.US_ASCII);
      long size = le32(header, 4);
      long padded = size + (size & 1);
      switch (fourCc) {
        case "VP8X" -> {
          if (canvas != null || bitstreams > 0 || size < VP8X_LENGTH) {
            return Optional.empty();
          }
          byte[] data = readExactly(in, VP8X_LENGTH);
          canvas = new Dimensions(le24(data, 4) + 1, le24(data, 7) + 1);
          in.skipNBytes(padded - VP8X_LENGTH);
        }
        case "ANMF" -> {
          if (canvas == null || size < ANMF_HEADER_LENGTH) {
            return Optional.empty();
          }
          byte[] data = readExactly(in, ANMF_HEADER_LENGTH);
          long right = 2L * le24(data, 0) + le24(data, 6) + 1;
          long bottom = 2L * le24(data, 3) + le24(data, 9) + 1;
          if (right > canvas.width() || bottom > canvas.height()) {
            return Optional.empty();
          }
          // Not skipped: the rest of the payload is the frame's own chunk sequence,
          // which this loop walks next.
        }
        case "VP8 ", "VP8L" -> {
          Optional<Dimensions> frame = bitstream(fourCc, in, size, padded);
          if (frame.isEmpty() || !fits(frame.get(), canvas)) {
            return Optional.empty();
          }
          bitstreams++;
          single = frame.get();
        }
        default -> in.skipNBytes(padded);
      }
    }
    if (canvas != null) {
      return bitstreams > 0 ? Optional.of(canvas) : Optional.empty();
    }
    return bitstreams == 1 ? Optional.of(single) : Optional.empty();
  }

  private static Optional<Dimensions> bitstream(
      String fourCc, InputStream in, long size, long padded) throws IOException {
    int needed = "VP8 ".equals(fourCc) ? VP8_HEADER_LENGTH : VP8L_HEADER_LENGTH;
    if (size < needed) {
      return Optional.empty();
    }
    byte[] data = readExactly(in, needed);
    in.skipNBytes(padded - needed);
    if ("VP8 ".equals(fourCc)) {
      if ((data[3] & 0xFF) != 0x9D || (data[4] & 0xFF) != 0x01 || (data[5] & 0xFF) != 0x2A) {
        return Optional.empty();
      }
      return Optional.of(new Dimensions(le16(data, 6) & 0x3FFF, le16(data, 8) & 0x3FFF));
    }
    if ((data[0] & 0xFF) != 0x2F) {
      return Optional.empty();
    }
    long bits = le32(data, 1);
    return Optional.of(
        new Dimensions((int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1));
  }

  private static boolean fits(Dimensions frame, Dimensions canvas) {
    if (frame.width() <= 0 || frame.height() <= 0) {
      return false;
    }
    return canvas == null || (frame.width() <= canvas.width() && frame.height() <= canvas.height());
  }

  private static byte[] readExactly(InputStream in, int length) throws IOException {
    byte[] data = in.readNBytes(length);
    if (data.length != length) {
      throw new java.io.EOFException("truncated WebP chunk");
    }
    return data;
  }

  private static int le16(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8;
  }

  private static int le24(byte[] b, int offset) {
    return le16(b, offset) | (b[offset + 2] & 0xFF) << 16;
  }

  private static long le32(byte[] b, int offset) {
    return le24(b, offset) | (b[offset + 3] & 0xFFL) << 24;
  }
}
