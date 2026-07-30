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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Decides what a document upload actually is, from its bytes (issues #245, #343).
 *
 * <p>The client's declared content type is never trusted — it is attacker-controlled, and an upload
 * that lies about itself would reach a parser or a converter chosen for a different format.
 *
 * <p>PDF is a five-byte prefix. DOCX is not: it is a ZIP container, so "starts with PK" would also
 * accept every spreadsheet, every JAR and every archive somebody dragged onto the dropzone. What
 * makes a file a Word document is a part named {@code word/document.xml}, and that is what is
 * looked for.
 */
public final class DocumentTypeSniffer {

  public static final String PDF = "application/pdf";
  public static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

  /** The part every Word document has and no other OOXML format does. */
  private static final String WORD_MAIN_PART = "word/document.xml";

  /**
   * How far into an archive the main part is looked for.
   *
   * <p>Reaching an entry means stepping over the payloads before it, and stepping over a deflated
   * payload inflates it — measured: walking past one 1 GB entry costs about 700 ms while
   * contributing only 1 MB to the upload. Since an upload is capped at its <em>compressed</em>
   * size, an unbounded walk sells a lot of CPU for very few bytes.
   *
   * <p>So the payloads in between are inflated <em>here</em>, deliberately and counted, rather than
   * skipped by {@code getNextEntry} where nothing can observe them. An entry that would take the
   * budget past this bound ends the scan before it is stepped over, which is what makes the bound
   * real rather than nominal.
   *
   * <p>It cannot be done from the sizes an entry declares: a real Word document (POI-written,
   * measured) reports {@code -1} for every size, because its local headers use data descriptors.
   * Trusting declared sizes would have rejected genuine files.
   *
   * <p>Both numbers sit far above anything legitimate — in that same sample the main part is the
   * fifth of fifteen entries, with 2 KB of content before it.
   */
  private static final int MAX_ENTRIES = 64;

  private static final long MAX_INFLATED_BYTES = 8L * 1024 * 1024;

  private DocumentTypeSniffer() {}

  /**
   * The content type of an upload, or null when it is not a format this server ingests.
   *
   * @param content the upload, positioned at the start; the caller owns and closes the stream
   */
  public static String sniff(InputStream content) throws IOException {
    byte[] prefix = new byte[PDF_MAGIC.length];
    int read = content.readNBytes(prefix, 0, prefix.length);
    if (read == PDF_MAGIC.length && startsWith(prefix, PDF_MAGIC)) {
      return PDF;
    }
    if (read < ZIP_MAGIC.length || !startsWith(prefix, ZIP_MAGIC)) {
      return null; // too short, or neither format — an empty upload lands here
    }
    // The prefix is already consumed and the caller's stream does not rewind, so
    // the archive is read from the two joined back together.
    return isWordArchive(
            new SequenceInputStream(new ByteArrayInputStream(prefix, 0, read), content))
        ? DOCX
        : null;
  }

  /** Whether a ZIP holds a Word document, within the bounds above. */
  private static boolean isWordArchive(InputStream archive) {
    // Closed for its Inflater, which holds native memory; the wrapper swallows the
    // close so the caller's stream — which the caller owns — stays open.
    try (ZipInputStream zip = new ZipInputStream(new NonClosing(archive))) {
      long budget = MAX_INFLATED_BYTES;
      byte[] scratch = new byte[8192];
      for (int entries = 0; entries < MAX_ENTRIES; entries++) {
        ZipEntry entry = zip.getNextEntry();
        if (entry == null) {
          return false;
        }
        if (WORD_MAIN_PART.equals(entry.getName())) {
          return true; // found without ever inflating it
        }
        budget = drain(zip, scratch, budget);
        if (budget < 0) {
          return false; // this entry alone would blow the budget: stop here
        }
      }
      return false;
    } catch (IOException | IllegalArgumentException e) {
      // Malformed, truncated or unreadable. Not a Word document, and not something
      // to hand to a converter; whatever is wrong with the bytes surfaces again
      // when the upload is stored.
      return false;
    }
  }

  /**
   * Consumes the current entry, returning what is left of the budget, or -1 when the entry runs
   * past it.
   *
   * <p>The point of reading it here is that the next {@code getNextEntry} then has nothing left to
   * step over, so no inflation happens anywhere this method cannot see.
   */
  private static long drain(ZipInputStream zip, byte[] scratch, long budget) throws IOException {
    long remaining = budget;
    int read;
    while ((read = zip.read(scratch, 0, scratch.length)) > 0) {
      remaining -= read;
      if (remaining < 0) {
        return -1;
      }
    }
    return remaining;
  }

  /** Keeps the caller's stream open when the ZIP reader closes. */
  private static final class NonClosing extends FilterInputStream {
    NonClosing(InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // deliberately not closing the wrapped stream
    }
  }

  private static boolean startsWith(byte[] value, byte[] magic) {
    if (value.length < magic.length) {
      return false;
    }
    for (int index = 0; index < magic.length; index++) {
      if (value[index] != magic[index]) {
        return false;
      }
    }
    return true;
  }
}
