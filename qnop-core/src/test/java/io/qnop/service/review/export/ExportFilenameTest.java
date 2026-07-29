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
package io.qnop.service.review.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The export's filename rules (issue #635 follow-up). */
class ExportFilenameTest {

  private static String forXlsx(String requested, String title) {
    return ExportFilename.forExport(requested, title, AnnotationExportFormat.XLSX);
  }

  @Test
  @DisplayName("defaults to <slug>-annotations.<ext>")
  void defaultsToTheSluggedTitle() {
    assertThat(forXlsx(null, "Vendor Agreement")).isEqualTo("vendor-agreement-annotations.xlsx");
    assertThat(ExportFilename.forExport(null, "Vendor Agreement", AnnotationExportFormat.DOCX))
        .isEqualTo("vendor-agreement-annotations.docx");
  }

  @Test
  @DisplayName("folds diacritics and collapses punctuation in the default")
  void slugifiesTheTitle() {
    assertThat(forXlsx(null, "Vertrag über Prüfungen (v2)"))
        .isEqualTo("vertrag-uber-prufungen-v2-annotations.xlsx");
  }

  @Test
  @DisplayName("a title with nothing sluggable still yields a usable name")
  void fallsBackWhenTheTitleSlugifiesToNothing() {
    assertThat(forXlsx(null, "→ ★ ←")).isEqualTo("annotations.xlsx");
    assertThat(forXlsx(null, null)).isEqualTo("annotations.xlsx");
    assertThat(forXlsx("   ", "  ")).isEqualTo("annotations.xlsx");
  }

  @Test
  @DisplayName("the user's own name wins, and always carries the format's extension")
  void honoursTheRequestedName() {
    assertThat(forXlsx("Q3 findings", "Vendor Agreement")).isEqualTo("Q3 findings.xlsx");
    // Restating the extension is not punished, but nor is it doubled.
    assertThat(forXlsx("Q3 findings.xlsx", "Vendor Agreement")).isEqualTo("Q3 findings.xlsx");
    // A different extension does not override the format — the bytes are XLSX.
    assertThat(forXlsx("Q3 findings.pdf", "Vendor Agreement")).isEqualTo("Q3 findings.pdf.xlsx");
  }

  @Test
  @DisplayName("a dot that is part of the name is kept")
  void keepsInternalDots() {
    assertThat(forXlsx("contract-v1.2", "Vendor Agreement")).isEqualTo("contract-v1.2.xlsx");
  }

  @Test
  @DisplayName("non-Latin names survive rather than being folded away")
  void keepsNonLatinNames() {
    // Only the default is slugified; a name the user typed is theirs, and the
    // header carries it UTF-8 encoded.
    assertThat(forXlsx("Prüfbericht", "Vendor Agreement")).isEqualTo("Prüfbericht.xlsx");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "evil\r\nX-Injected: yes",
        "../../etc/passwd",
        "..\\\\windows\\\\system32",
        "quote\"name",
        "nul\0byte",
      })
  @DisplayName("nothing that could escape the header or the folder survives")
  void refusesDangerousNames(String dangerous) {
    String name = forXlsx(dangerous, "Vendor Agreement");

    // The header is the reason this matters: a newline would end it early, a
    // quote would close the filename token, and a separator would aim the
    // download somewhere other than the downloads folder.
    assertThat(name).doesNotContain("\r", "\n", "/", "\\", "\"");
    // Spaces are deliberately NOT in that list: they are legal in a filename
    // and people use them. It is the control characters that must not survive.
    assertThat(name.chars().noneMatch(Character::isISOControl)).isTrue();
    assertThat(name).endsWith(".xlsx");
  }

  @Test
  @DisplayName("a name of only dots cannot become a directory reference")
  void refusesDotOnlyNames() {
    assertThat(forXlsx("..", "Vendor Agreement")).isEqualTo("vendor-agreement-annotations.xlsx");
    assertThat(forXlsx(".", "Vendor Agreement")).isEqualTo("vendor-agreement-annotations.xlsx");
  }

  @Test
  @DisplayName("an over-long name is capped rather than rejected")
  void capsTheLength() {
    String name = forXlsx("x".repeat(500), "Vendor Agreement");

    assertThat(name).hasSize(ExportFilename.MAX_BASE_LENGTH + ".xlsx".length());
  }
}
