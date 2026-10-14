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

import io.qnop.service.FieldErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Keeps the field-error codes (issue #836) in step across their three homes: the enum, the OpenAPI
 * description of {@code FieldError.code}, and the UI's en/de catalogs that word them. A code
 * missing from a catalog would silently fall back to the English server message.
 */
class FieldErrorCodeContractTest {

  private static final Path REPO = Path.of("..");

  private static String read(String path) throws IOException {
    return Files.readString(REPO.resolve(path));
  }

  @ParameterizedTest
  @EnumSource(FieldErrorCode.class)
  void everyCodeIsDocumentedAndWorded(FieldErrorCode code) throws IOException {
    // A code that counts its bound (TOO_SHORT, TOO_LONG) is worded as i18next plural forms.
    String[] keys = {"\"" + code.name() + "\":", "\"" + code.name() + "_other\":"};
    assertThat(read("qnop-api/src/main/resources/openapi/openapi.yaml"))
        .contains("`" + code.name() + "`");
    assertThat(read("qnop-ui/src/i18n/en/common.json")).containsAnyOf(keys);
    assertThat(read("qnop-ui/src/i18n/de/common.json")).containsAnyOf(keys);
  }

  @Test
  void theRepositoryLayoutIsWhereTheTestExpectsIt() {
    assertThat(REPO.resolve("qnop-ui/src/i18n/en/common.json")).exists();
  }
}
