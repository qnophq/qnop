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
package io.qnop.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.service.review.Result.Err;
import io.qnop.service.review.Result.Ok;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the sealed {@link Result} value type (issue #320). */
class ResultTest {

  @Test
  @DisplayName("ok carries the value and matches the Ok arm")
  void okCarriesValue() {
    Result<String, Integer> result = Result.ok("done");

    assertThat(result).isInstanceOf(Ok.class);
    assertThat(describe(result)).isEqualTo("ok:done");
  }

  @Test
  @DisplayName("err carries the error and matches the Err arm")
  void errCarriesError() {
    Result<String, Integer> result = Result.err(404);

    assertThat(result).isInstanceOf(Err.class);
    assertThat(describe(result)).isEqualTo("err:404");
  }

  /** Exercises exhaustive pattern matching over the sealed hierarchy. */
  private static String describe(Result<String, Integer> result) {
    return switch (result) {
      case Ok<String, Integer> ok -> "ok:" + ok.value();
      case Err<String, Integer> err -> "err:" + err.error();
    };
  }
}
