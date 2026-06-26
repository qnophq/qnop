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
package io.qnop.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailPlaceholderValidatorTest {

  @Test
  @DisplayName("collects ordinary variable references, sorted and de-duplicated")
  void collectsVariables() {
    assertThat(
            MailPlaceholderValidator.referencedPlaceholders(
                "Hi {{ recipientName }}, open {{actionUrl}} ({{recipientName}})"))
        .containsExactly("actionUrl", "recipientName");
  }

  @Test
  @DisplayName("skips comments, partials, section markers, triple-stache and unescaped tags")
  void skipsNonVariableTags() {
    String body =
        "{{! a plain comment }}"
            + "{{> partial}}"
            + "{{#section}}{{/section}}{{^inv}}"
            + "{{{tripleVar}}}"
            + "{{& unescapedVar}}"
            + "{{realVar}}";

    assertThat(MailPlaceholderValidator.referencedPlaceholders(body)).containsExactly("realVar");
  }

  @Test
  @DisplayName("unknownPlaceholders reports only references outside the allowed set")
  void reportsUnknown() {
    Set<String> allowed = Set.of("siteName", "actionUrl");

    assertThat(
            MailPlaceholderValidator.unknownPlaceholders(
                allowed, "{{siteName}} subject", "Body {{actionUrl}} and {{rogue}}", null))
        .containsExactly("rogue");

    assertThat(
            MailPlaceholderValidator.unknownPlaceholders(
                allowed, "{{siteName}}", "{{actionUrl}}", "<p>{{siteName}}</p>"))
        .isEmpty();
  }
}
