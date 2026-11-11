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
package io.qnop.service.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * Process isolation of the office converter (issue #908), exercised against a stub executable that
 * stands in for {@code soffice} — the real suite is not needed to test how it is launched.
 */
class LibreOfficeConverterTest {

  /** Parses the arguments the converter passes, the way the real binary would see them. */
  private static final String STUB_PRELUDE =
      """
      #!/bin/sh
      [ "$1" = "--version" ] && exit 0
      outdir=""; profile=""; prev=""
      for a in "$@"; do
        [ "$prev" = "--outdir" ] && outdir="$a"
        case "$a" in -env:UserInstallation=file://*) profile="${a#-env:UserInstallation=file://}";; esac
        prev="$a"
      done
      """;

  private static final Set<String> ALLOWED_VARIABLES =
      Set.of("PATH", "HOME", "TMPDIR", "LANG", "SAL_USE_VCLPLUGIN");

  /** Set by the stub's own shell, not passed in by the converter. */
  private static final Set<String> SHELL_VARIABLES = Set.of("PWD", "OLDPWD", "SHLVL", "_");

  @TempDir Path dir;

  @BeforeEach
  void requiresAPosixShell() {
    assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
  }

  @Test
  void childSeesOnlyTheAllowlistedEnvironment() throws IOException {
    LibreOfficeConverter converter = converter(stub("env > \"$outdir/document.pdf\""), null, null);

    String environment = new String(converter.toPdf(new byte[] {1}, "docx"), UTF8);

    Set<String> names =
        environment
            .lines()
            .filter(line -> line.contains("="))
            .map(line -> line.substring(0, line.indexOf('=')))
            .filter(name -> !SHELL_VARIABLES.contains(name))
            .collect(Collectors.toSet());
    assertThat(names).isSubsetOf(ALLOWED_VARIABLES).contains("PATH", "HOME", "LANG");
    assertThat(environment).doesNotContain("QNOP_");
  }

  @Test
  void childEnvironmentDropsEverythingButTheAllowlist() {
    Map<String, String> parent =
        Map.of(
            "PATH", "/opt/bin:/usr/bin",
            "QNOP_JWT_SECRET", "s",
            "QNOP_S3_SECRET_KEY", "s",
            "SPRING_DATASOURCE_PASSWORD", "s",
            "LANG", "de_DE.UTF-8");

    Map<String, String> child = LibreOfficeConverter.childEnvironment(parent, dir);

    assertThat(child.keySet()).isEqualTo(ALLOWED_VARIABLES);
    assertThat(child).containsEntry("PATH", "/opt/bin:/usr/bin");
    assertThat(child).containsEntry("HOME", dir.toString());
    assertThat(child.get("TMPDIR")).startsWith(dir.toString());
  }

  @Test
  void runsWithAHardenedPerRunProfile() throws IOException {
    LibreOfficeConverter converter =
        converter(
            stub("cat \"$profile/user/registrymodifications.xcu\" > \"$outdir/document.pdf\""),
            null,
            null);

    String registry = new String(converter.toPdf(new byte[] {1}, "docx"), UTF8);

    assertThat(registry)
        .contains("DisableMacrosExecution\" oor:op=\"fuse\"><value>true</value>")
        .contains("MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value>")
        .contains("/org.openoffice.Office.Writer/Content/Update")
        .contains("Link\" oor:op=\"fuse\"><value>2</value>");
  }

  @Test
  void timeoutKillsTheWholeProcessTree() throws Exception {
    Path pidFile = dir.resolve("child.pid");
    LibreOfficeConverter converter =
        converter(
            stub("sleep 300 &\necho $! > '" + pidFile + "'\nwait"), Duration.ofSeconds(2), null);

    assertThatThrownBy(() -> converter.toPdf(new byte[] {1}, "docx"))
        .isInstanceOf(OfficeConversionException.class)
        .hasMessageContaining("did not finish");

    long childPid = Long.parseLong(Files.readString(pidFile).strip());
    Optional<ProcessHandle> child = ProcessHandle.of(childPid);
    if (child.isPresent()) {
      child.get().onExit().get(10, TimeUnit.SECONDS);
      assertThat(child.get().isAlive()).isFalse();
    }
  }

  @Test
  void oversizedOutputIsRejected() throws IOException {
    LibreOfficeConverter converter =
        converter(
            stub("head -c 4096 /dev/zero > \"$outdir/document.pdf\""),
            null,
            DataSize.ofBytes(1024));

    assertThatThrownBy(() -> converter.toPdf(new byte[] {1}, "docx"))
        .isInstanceOf(OfficeConversionException.class)
        .hasMessageContaining("output limit");
  }

  @Test
  void oversizedInputIsRejectedBeforeTheConverterRuns() throws IOException {
    Path marker = dir.resolve("ran");
    LibreOfficeConverter converter =
        new LibreOfficeConverter(
            new OfficeConverterProperties(
                stub("touch '" + marker + "'").toString(),
                null,
                0,
                null,
                DataSize.ofBytes(8),
                null));

    byte[] source = new byte[9];
    Arrays.fill(source, (byte) 1);
    assertThatThrownBy(() -> converter.toPdf(source, "docx"))
        .isInstanceOf(OfficeConversionException.class)
        .hasMessageContaining("input limit");
    assertThat(marker).doesNotExist();
  }

  private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

  private LibreOfficeConverter converter(Path binary, Duration timeout, DataSize maxOutput) {
    return new LibreOfficeConverter(
        new OfficeConverterProperties(binary.toString(), timeout, 0, null, null, maxOutput));
  }

  private Path stub(String body) throws IOException {
    Path script = Files.createTempFile(dir, "soffice-", ".sh");
    Files.writeString(script, STUB_PRELUDE + body + "\n", UTF8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}
