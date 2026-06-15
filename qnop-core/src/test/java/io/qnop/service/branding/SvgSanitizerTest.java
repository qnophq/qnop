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
package io.qnop.service.branding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Security-focused tests for {@link SvgSanitizer} (issue #23). */
class SvgSanitizerTest {

  private static String sanitize(String svg) {
    return new String(
        SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
  }

  @Test
  void stripsScriptAndEventHandlersButKeepsShapes() {
    String out =
        sanitize(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<script>alert(1)</script>"
                + "<rect width=\"10\" height=\"10\" onload=\"alert(2)\"/>"
                + "<path d=\"M0 0L10 10\"/>"
                + "</svg>");

    assertFalse(out.contains("script"), out);
    assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("onload"), out);
    assertTrue(out.contains("<path"), out);
    assertTrue(out.contains("<rect"), out);
  }

  @Test
  void stripsUnsafeHrefsButKeepsFragmentReferences() {
    String out =
        sanitize(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " viewBox=\"0 0 10 10\">"
                + "<a xlink:href=\"javascript:alert(1)\"><rect width=\"5\" height=\"5\"/></a>"
                + "<use xlink:href=\"#icon\"/>"
                + "</svg>");

    assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("javascript:"), out);
    assertFalse(out.contains("<a "), out); // <a> is not on the allowlist
    assertTrue(out.contains("#icon"), out); // fragment reference on <use> survives
  }

  @Test
  void rejectsDoctypeToBlockXxe() {
    String xxe =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<svg xmlns=\"http://www.w3.org/2000/svg\"><desc>&xxe;</desc></svg>";

    assertThrows(
        IllegalArgumentException.class,
        () -> SvgSanitizer.sanitize(xxe.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsNonSvgRoot() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SvgSanitizer.sanitize("<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8)));
  }
}
