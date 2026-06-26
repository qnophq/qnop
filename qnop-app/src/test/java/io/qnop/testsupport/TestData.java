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
package io.qnop.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Access to the repo-level shared test fixtures under {@code testdata/} (see {@code
 * testdata/README.md}). The directory is located from the {@code qnop.testdata.dir} system property
 * set by the Gradle convention plugin, falling back to walking up from the working directory so the
 * tests also run from an IDE without extra configuration.
 */
public final class TestData {

  private static final Path ROOT = resolveRoot();

  private TestData() {}

  /** Resolves a fixture path, e.g. {@code TestData.path("branding/logo-light.png")}. */
  public static Path path(String relative) {
    return ROOT.resolve(relative);
  }

  /** Reads a fixture's bytes, e.g. {@code TestData.bytes("branding/logo-light.png")}. */
  public static byte[] bytes(String relative) {
    try {
      return Files.readAllBytes(path(relative));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read test fixture: " + relative, e);
    }
  }

  private static Path resolveRoot() {
    String configured = System.getProperty("qnop.testdata.dir");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    // Fallback for IDE runs: walk up to the repo root (the build's settings file) and use its
    // testdata directory.
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("testdata");
      if (Files.isDirectory(candidate) && Files.exists(dir.resolve("settings.gradle.kts"))) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Could not locate the testdata directory; set -Dqnop.testdata.dir");
  }
}
