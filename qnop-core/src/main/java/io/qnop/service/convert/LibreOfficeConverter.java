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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts through a LibreOffice subprocess (issue #639).
 *
 * <p>A subprocess and not a library, deliberately. LibreOffice is MPL/LGPL, and ADR-0007 allows
 * copyleft tools only where nothing links against them; spawning the binary keeps that boundary
 * absolute and costs qnop no new Java dependency at all. ADR-0010 made the same call for DOCX
 * ingest, so this is one installation serving both.
 *
 * <p>Two details are not optional and are the usual reason a naive invocation fails in production.
 * Each run gets its <em>own</em> user profile: LibreOffice refuses to start a second instance
 * against a profile already in use, so without it two concurrent exports would fight and one would
 * fail. And each run gets a deadline: a hung office process would otherwise hold a request thread
 * until the server is restarted.
 */
@Component
public class LibreOfficeConverter implements OfficeConverter {

  private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);

  /** Enough for a stack of LibreOffice complaints, far short of anything that fills a log file. */
  private static final int MAX_CAPTURED_OUTPUT = 4_000;

  /** How long to wait for the reader once the process is already gone. */
  private static final Duration OUTPUT_DRAIN_GRACE = Duration.ofSeconds(2);

  private final OfficeConverterProperties properties;

  /**
   * Whether the binary answered when first asked.
   *
   * <p>Cached for the process's lifetime: installing an office suite into a running container is
   * not a thing that happens, and probing on every call would spawn a process per request just to
   * decide whether to spawn a process.
   */
  private volatile Boolean available;

  public LibreOfficeConverter(OfficeConverterProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean isAvailable() {
    Boolean cached = available;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (available == null) {
        available = probe();
      }
      return available;
    }
  }

  /** Asks the binary for its version — the cheapest question that proves it can actually run. */
  private boolean probe() {
    try {
      Process process =
          new ProcessBuilder(properties.binary(), "--version")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        log.warn("{} --version did not answer; PDF export stays unavailable", properties.binary());
        return false;
      }
      boolean ok = process.exitValue() == 0;
      log.info(
          ok
              ? "Office converter available ({}), PDF export enabled"
              : "Office converter at {} exited non-zero; PDF export stays unavailable",
          properties.binary());
      return ok;
    } catch (IOException e) {
      // The overwhelmingly common case: no office suite on this machine. Expected
      // on a developer laptop, so it is stated once at INFO, not warned about.
      log.info(
          "No office converter at '{}' — PDF export is unavailable on this server",
          properties.binary());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public byte[] toPdf(byte[] source, String sourceExtension) {
    if (!isAvailable()) {
      throw new OfficeConversionException(
          "no office converter is installed on this server (looked for '"
              + properties.binary()
              + "')");
    }
    Path workspace = null;
    try {
      workspace = Files.createTempDirectory("qnop-convert-");
      Path input = workspace.resolve("document." + sourceExtension);
      Files.write(input, source);
      long started = System.nanoTime();
      run(workspace, input);
      log.info(
          "Converted {} bytes of {} to PDF in {} ms",
          source.length,
          sourceExtension,
          (System.nanoTime() - started) / 1_000_000);

      Path output = workspace.resolve("document.pdf");
      if (!Files.exists(output)) {
        // It started, it finished, it wrote nothing. That is how LibreOffice says
        // "I could not read this", so the document is the problem and a retry
        // would only repeat it.
        throw OfficeConversionException.unreadableDocument(
            "the converter reported success but produced no PDF for " + input.getFileName());
      }
      return Files.readAllBytes(output);
    } catch (IOException e) {
      throw new OfficeConversionException("could not convert a document to PDF", e);
    } finally {
      deleteRecursively(workspace);
    }
  }

  private void run(Path workspace, Path input) throws IOException {
    List<String> command =
        List.of(
            properties.binary(),
            "--headless",
            "--norestore",
            "--invisible",
            // Its own profile per run: LibreOffice will not start against a profile
            // another instance holds, so a shared one turns concurrent exports into
            // sporadic failures.
            "-env:UserInstallation=file://" + workspace.resolve("profile"),
            "--convert-to",
            "pdf",
            "--outdir",
            workspace.toString(),
            input.toString());

    // Kept rather than discarded (issue #659): a non-zero exit used to leave nothing
    // but a status number, and what LibreOffice actually complained about is the one
    // thing a developer needs. The workspace path holds no user-chosen name — the
    // input is always "document.<ext>" under a generated temp directory — so the
    // output carries nothing personal.
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    StringBuilder output = new StringBuilder();
    Thread drain = drainInto(process, output);
    try {
      if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        log.warn(
            "The office converter did not finish within {}; it said: {}",
            properties.timeout(),
            saidOrNothing(drain, output));
        throw new OfficeConversionException(
            "the converter did not finish within " + properties.timeout());
      }
      if (process.exitValue() != 0) {
        log.warn(
            "The office converter exited with status {}; it said: {}",
            process.exitValue(),
            saidOrNothing(drain, output));
        throw new OfficeConversionException(
            "the converter exited with status " + process.exitValue());
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new OfficeConversionException("interrupted while converting a document", e);
    }
  }

  /**
   * Reads the process output on its own thread.
   *
   * <p>Not optional: with the pipe kept rather than discarded, a converter that writes more than
   * the OS buffer holds would block forever on a reader that only starts after {@code waitFor}. The
   * capture is bounded for the same reason the buffer is — a runaway process must not become a
   * runaway log line.
   */
  private static Thread drainInto(Process process, StringBuilder sink) {
    Thread thread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                int character;
                while ((character = reader.read()) != -1) {
                  if (sink.length() < MAX_CAPTURED_OUTPUT) {
                    sink.append((char) character);
                  }
                }
              } catch (IOException e) {
                // The process is gone or the pipe broke; there is nothing left to read
                // and nothing a failed drain changes about the conversion's outcome.
              }
            },
            "office-converter-output");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Whatever the converter managed to say, collapsed onto one line. */
  private static String saidOrNothing(Thread drain, StringBuilder output) {
    try {
      drain.join(OUTPUT_DRAIN_GRACE.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    String said = output.toString().replaceAll("\\s+", " ").trim();
    return said.isEmpty() ? "(nothing)" : said;
  }

  /** Best-effort cleanup: a leftover temp directory must not fail a conversion that succeeded. */
  private static void deleteRecursively(Path directory) {
    if (directory == null) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(LibreOfficeConverter::deleteQuietly);
    } catch (IOException e) {
      log.warn("Could not clean up the conversion workspace {}", directory, e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.debug("Could not delete {}", path, e);
    }
  }
}
