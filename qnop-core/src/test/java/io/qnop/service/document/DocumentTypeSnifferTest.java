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
package io.qnop.service.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the server accepts as a document, decided from the bytes (issues #245, #343). */
class DocumentTypeSnifferTest {

  private static String sniff(byte[] content) throws IOException {
    try (InputStream in = new ByteArrayInputStream(content)) {
      return DocumentTypeSniffer.sniff(in);
    }
  }

  /** A ZIP with the given entries, in order. */
  private static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] xml(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  /** The shape a Word document actually has: content types, relations, then the main part. */
  private static byte[] wordArchive() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("[Content_Types].xml", xml("<Types/>"));
    entries.put("_rels/.rels", xml("<Relationships/>"));
    entries.put("docProps/app.xml", xml("<Properties/>"));
    entries.put("word/document.xml", xml("<w:document/>"));
    return zip(entries);
  }

  @Test
  @DisplayName("a PDF is recognised by its prefix")
  void recognisesPdf() throws Exception {
    assertThat(sniff("%PDF-1.7\nbody".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(DocumentTypeSniffer.PDF);
  }

  @Test
  @DisplayName("a Word document is recognised by its main part, not by being a ZIP")
  void recognisesWord() throws Exception {
    assertThat(sniff(wordArchive())).isEqualTo(DocumentTypeSniffer.DOCX);
  }

  @Test
  @DisplayName("another OOXML format is not a Word document")
  void refusesOtherOoxml() throws Exception {
    // A spreadsheet has the same container and the same content-types part; only
    // the main part differs. Accepting it would send an XLSX to a converter told
    // it was reading Word.
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("[Content_Types].xml", xml("<Types/>"));
    entries.put("xl/workbook.xml", xml("<workbook/>"));

    assertThat(sniff(zip(entries))).isNull();
  }

  @Test
  @DisplayName("a plain archive is not a document")
  void refusesPlainZip() throws Exception {
    assertThat(sniff(zip(Map.of("notes.txt", xml("hello"))))).isNull();
  }

  @Test
  @DisplayName("empty, short and unrecognised uploads are refused rather than guessed at")
  void refusesEverythingElse() throws Exception {
    assertThat(sniff(new byte[0])).isNull();
    assertThat(sniff("PK".getBytes(StandardCharsets.UTF_8))).isNull();
    assertThat(sniff("%PD".getBytes(StandardCharsets.UTF_8))).isNull();
    assertThat(sniff("<html></html>".getBytes(StandardCharsets.UTF_8))).isNull();
    // Truncated mid-archive: the walk must end, not throw.
    byte[] word = wordArchive();
    assertThat(sniff(java.util.Arrays.copyOf(word, 40))).isNull();
  }

  @Test
  @DisplayName("an archive that hides its main part behind a huge payload is refused, quickly")
  void refusesCompressionBombs() throws Exception {
    // Stepping over a deflated entry inflates it, and an upload is only capped at
    // its compressed size — measured, one 1 GB entry costs ~700 ms to walk past
    // while contributing about 1 MB to the upload. So the scan is bounded, and a
    // file that puts its main part behind such a payload does not get scanned.
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("payload.bin", new byte[64 * 1024 * 1024]);
    entries.put("word/document.xml", xml("<w:document/>"));
    byte[] bomb = zip(entries);

    long start = System.nanoTime();
    String type = sniff(bomb);
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(type).isNull();
    assertThat(millis).isLessThan(2_000);
  }

  @Test
  @DisplayName("the main part is still found behind the parts a real document puts first")
  void scansPastTheUsualPreamble() throws Exception {
    // The bound must not be so tight that an ordinary document trips it: media can
    // precede the main part, and only an unreasonable amount of it is refused.
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("[Content_Types].xml", xml("<Types/>"));
    for (int index = 0; index < 20; index++) {
      entries.put("word/media/image" + index + ".png", new byte[32 * 1024]);
    }
    entries.put("word/document.xml", xml("<w:document/>"));

    assertThat(sniff(zip(entries))).isEqualTo(DocumentTypeSniffer.DOCX);
  }

  // Issue #601: common image magics are recognized (acceptance stays the gate's question).

  @Test
  void sniffsPngJpegGifAndTiff() throws Exception {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    byte[] gif = {'G', 'I', 'F', '8', '9', 'a'};
    byte[] tiffLe = {0x49, 0x49, 0x2A, 0x00, 0, 0};
    org.assertj.core.api.Assertions.assertThat(sniff(png)).isEqualTo(DocumentTypeSniffer.PNG);
    org.assertj.core.api.Assertions.assertThat(sniff(jpeg)).isEqualTo(DocumentTypeSniffer.JPEG);
    org.assertj.core.api.Assertions.assertThat(sniff(gif)).isEqualTo(DocumentTypeSniffer.GIF);
    org.assertj.core.api.Assertions.assertThat(sniff(tiffLe)).isEqualTo(DocumentTypeSniffer.TIFF);
  }
}
