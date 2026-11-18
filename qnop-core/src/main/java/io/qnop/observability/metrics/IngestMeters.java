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
package io.qnop.observability.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Meters of document ingest (ADR-0032): what is uploaded and what extraction makes of it. The
 * {@code format} tag is one of {@link Format}, derived from the sniffed content type and never from
 * a file name.
 */
@Component
public class IngestMeters {

  public static final String EXTRACTION = "qnop.document.extraction";
  public static final String UPLOAD_SIZE = "qnop.document.upload.size";
  public static final String SURFACES = "qnop.document.surfaces";

  private static final String PDF_CONTENT_TYPE = "application/pdf";
  private static final String DOCX_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  /** The uploaded format, as far as a dashboard needs to tell them apart. */
  public enum Format {
    PDF,
    DOCX,
    OTHER;

    public static Format ofContentType(String contentType) {
      if (PDF_CONTENT_TYPE.equals(contentType)) {
        return PDF;
      }
      if (DOCX_CONTENT_TYPE.equals(contentType)) {
        return DOCX;
      }
      return OTHER;
    }
  }

  /** How an extraction attempt ended. */
  public enum Outcome {
    /** The version is READY. */
    SUCCESS,
    /** The document itself cannot be processed; the version is FAILED for good. */
    REJECTED,
    /** Something around it broke (storage, database); the queue retries. */
    ERROR
  }

  private static final Duration[] EXTRACTION_BUCKETS = {
    Duration.ofMillis(100),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofSeconds(5),
    Duration.ofSeconds(15),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2),
    Duration.ofMinutes(5)
  };

  private static final long MEBIBYTE = 1024L * 1024L;
  private static final double[] SIZE_BUCKETS = {
    MEBIBYTE / 10.0, MEBIBYTE, 5.0 * MEBIBYTE, 10.0 * MEBIBYTE, 25.0 * MEBIBYTE, 50.0 * MEBIBYTE
  };
  private static final double[] SURFACE_BUCKETS = {1, 5, 10, 25, 50, 100, 250, 500};

  private final MeterRegistry registry;

  public IngestMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  /** An extraction attempt finished; this includes any conversion to PDF that preceded it. */
  public void extracted(Format format, Outcome outcome, Duration duration) {
    Timer.builder(EXTRACTION)
        .description("Duration of extracting one document version, conversion included")
        .tag("format", MeterTags.of(format))
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(EXTRACTION_BUCKETS)
        .register(registry)
        .record(duration);
  }

  /** A document version of {@code bytes} was accepted. */
  public void uploaded(Format format, long bytes) {
    DistributionSummary.builder(UPLOAD_SIZE)
        .description("Size of accepted document versions")
        .baseUnit("bytes")
        .tag("format", MeterTags.of(format))
        .serviceLevelObjectives(SIZE_BUCKETS)
        .register(registry)
        .record(bytes);
  }

  /** A version was extracted into {@code surfaces} renderable surfaces (pages, for a PDF). */
  public void surfaces(Format format, int surfaces) {
    DistributionSummary.builder(SURFACES)
        .description("Renderable surfaces (pages) per extracted document version")
        .tag("format", MeterTags.of(format))
        .serviceLevelObjectives(SURFACE_BUCKETS)
        .register(registry)
        .record(surfaces);
  }
}
