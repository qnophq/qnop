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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

/** Extension derivation from the stored content type (issue #328). */
class DownloadFilenameTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "application/pdf, .pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document, .docx",
    "text/markdown, .md",
    "text/x-markdown, .md",
    "'text/markdown; charset=utf-8', .md", // parameters are ignored
    "APPLICATION/PDF, .pdf", // case-insensitive
    "application/octet-stream, ''", // unknown → no (misleading) extension
  })
  void derivesExtensionFromContentType(String contentType, String expected) {
    assertThat(DownloadFilename.extensionFor(contentType)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullSource
  void nullContentTypeHasNoExtension(String contentType) {
    assertThat(DownloadFilename.extensionFor(contentType)).isEmpty();
  }

  @Test
  void composesTitleVersionAndExtension() {
    assertThat(DownloadFilename.forVersion("Quarterly report", 3, "application/pdf"))
        .isEqualTo("Quarterly report-v3.pdf");
  }

  @Test
  void unknownTypeStillProducesAName() {
    assertThat(DownloadFilename.forVersion("Doc", 1, "application/octet-stream"))
        .isEqualTo("Doc-v1");
  }
}
