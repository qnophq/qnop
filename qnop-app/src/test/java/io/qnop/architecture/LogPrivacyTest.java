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
package io.qnop.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No personal data in the logs (issue #659, ADR-0054).
 *
 * <p>A log file has no access control, no retention policy and no subject-access route. Ids belong
 * there; the things people typed do not — a review title is regularly somebody's name, and so is an
 * uploaded file name.
 *
 * <p>Deliberately a source scan rather than an ArchUnit rule. ArchUnit sees that a class calls
 * {@code Document.getTitle()} and that it calls {@code Logger.info()}, never that the first is an
 * argument to the second; the data flow this is about is invisible in the dependency model. Reading
 * the text finds exactly the shape that matters, and it stays a lint — a wrong flag is silenced by
 * not writing the value into the log, which is the point.
 */
class LogPrivacyTest {

  /**
   * Accessors whose value is user-entered.
   *
   * <p>Not a guess at what is sensitive: each of these returns something a person typed, which is
   * why none of them can be assumed harmless. New ones belong here as they appear.
   */
  private static final List<String> FORBIDDEN =
      List.of(
          "getTitle()",
          ".title()",
          "getEmail()",
          ".email()",
          "getDisplayName()",
          ".displayName()",
          "getUsername()",
          ".username()",
          "getFileName()",
          ".fileName()",
          "getComment()",
          ".comment()",
          "getExcerpt()",
          ".excerpt()",
          // Bare identifiers too, for the case an accessor list cannot see: a
          // local variable already holding the value. Only names with one
          // possible meaning — `to` is deliberately absent, because a version
          // diff legitimately logs a `from`/`to` pair of numbers.
          "email",
          "emailAddress",
          "recipientEmail",
          "displayName",
          "fileName",
          "username");

  private static final Pattern LOG_CALL =
      Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\(");

  @Test
  @DisplayName("no log statement passes something a user typed")
  void logsCarryNoPersonalData() {
    List<String> offences = new ArrayList<>();
    for (Path source : mainSources()) {
      String code = stripComments(read(source));
      Matcher matcher = LOG_CALL.matcher(code);
      while (matcher.find()) {
        String call = argumentsOf(code, matcher.end() - 1);
        FORBIDDEN.stream()
            .filter(call::contains)
            .forEach(
                forbidden ->
                    offences.add(
                        source.getFileName()
                            + ": log call passes "
                            + forbidden
                            + " → "
                            + oneLine(call)));
      }
    }

    assertThat(offences)
        .withFailMessage(
            "These log statements would write user-entered text into a log file (issue #659):%n%s%n%n"
                + "Log the id instead; the value itself belongs in the database or the audit "
                + "trail, where access is controlled.",
            String.join(System.lineSeparator(), offences))
        .isEmpty();
  }

  @Test
  @DisplayName("the scan actually reads the sources it claims to")
  void theScanSeesTheCode() {
    // A privacy test that silently scanned nothing would pass forever. This is
    // the guard on the guard.
    List<Path> sources = mainSources();
    assertThat(sources).hasSizeGreaterThan(200);
    assertThat(sources.stream().map(Path::toString)).anyMatch(path -> path.contains("qnop-core"));
    assertThat(sources.stream().map(Path::toString)).anyMatch(path -> path.contains("qnop-app"));

    long logging = sources.stream().filter(source -> LOG_CALL.matcher(read(source)).find()).count();
    assertThat(logging).isGreaterThan(20);
  }

  /** Every production source file of the two modules that hold logic. */
  private static List<Path> mainSources() {
    Path root = repositoryRoot();
    List<Path> sources = new ArrayList<>();
    for (String module : List.of("qnop-core", "qnop-app")) {
      Path main = root.resolve(module).resolve("src/main/java");
      if (!Files.isDirectory(main)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(main)) {
        walk.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return sources;
  }

  /** Walks up from the working directory until the settings file names the repository root. */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IllegalStateException("could not locate the repository root");
    }
    return candidate;
  }

  /**
   * The text between the call's parentheses.
   *
   * <p>Counted rather than matched to a closing bracket, because a log call routinely nests calls
   * and its arguments span several lines.
   */
  private static String argumentsOf(String code, int openParen) {
    int depth = 0;
    for (int index = openParen; index < code.length(); index++) {
      char character = code.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        depth--;
        if (depth == 0) {
          return code.substring(openParen + 1, index);
        }
      }
    }
    return code.substring(openParen);
  }

  /**
   * Removes comments and string literals.
   *
   * <p>Both would otherwise produce false alarms: this very file names the forbidden accessors in
   * prose, and a message like "could not read the title" contains one as plain text.
   */
  private static String stripComments(String code) {
    StringBuilder out = new StringBuilder(code.length());
    int index = 0;
    while (index < code.length()) {
      char character = code.charAt(index);
      if (character == '/' && index + 1 < code.length() && code.charAt(index + 1) == '/') {
        while (index < code.length() && code.charAt(index) != '\n') {
          index++;
        }
      } else if (character == '/' && index + 1 < code.length() && code.charAt(index + 1) == '*') {
        index += 2;
        while (index + 1 < code.length()
            && !(code.charAt(index) == '*' && code.charAt(index + 1) == '/')) {
          index++;
        }
        index += 2;
      } else if (character == '"') {
        out.append(' '); // keep the argument shape, drop the content
        index++;
        while (index < code.length() && code.charAt(index) != '"') {
          index += code.charAt(index) == '\\' ? 2 : 1;
        }
        index++;
      } else {
        out.append(character);
        index++;
      }
    }
    return out.toString();
  }

  private static String oneLine(String text) {
    String collapsed = text.replaceAll("\\s+", " ").trim();
    return collapsed.length() <= 120 ? collapsed : collapsed.substring(0, 120) + "…";
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
