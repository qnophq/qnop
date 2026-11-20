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
package io.qnop.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.testsupport.SeededIntegrationTest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The log levels the application ships with, proven against the console (issue #986).
 *
 * <p>PDFBox logs through commons-logging, not SLF4J. A level set in {@code application.yml} only
 * reaches it if commons-logging hands over to Logback, so these tests log through PDFBox's own
 * logging API and read what was written.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogLevelsIT extends SeededIntegrationTest {

  @Test
  @DisplayName("PDFBox's font substitution warnings stay out of the log, its errors do not")
  void pdfBoxFontWarningsAreSilenced(CapturedOutput output) {
    // The logger PDFBox itself uses for "Using fallback font LiberationSans for Helvetica".
    Log log = LogFactory.getLog(PDType1Font.class);

    log.warn("issue-986 font substitution warning");
    log.error("issue-986 font error");

    assertThat(log.isWarnEnabled()).isFalse();
    assertThat(output.getOut()).doesNotContain("issue-986 font substitution warning");
    assertThat(output.getOut()).contains("issue-986 font error");
  }

  @Test
  @DisplayName("the rest of PDFBox still warns, and so does the provider of the server's fonts")
  void otherPdfBoxWarningsStillShow(CapturedOutput output) {
    LogFactory.getLog(org.apache.pdfbox.pdfparser.COSParser.class).warn("issue-986 parser warning");
    // Package-private in PDFBox, so named rather than referenced.
    LogFactory.getLog("org.apache.pdfbox.pdmodel.font.FileSystemFontProvider")
        .warn("issue-986 font cache warning");

    assertThat(output.getOut())
        .contains("issue-986 parser warning")
        .contains("issue-986 font cache warning");
  }
}
