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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.observability.metrics.IngestMeters.Format;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ingest meters and their closed set of formats (issue #962). */
class IngestMetersTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngestMeters meters = new IngestMeters(registry);

  @Test
  @DisplayName("maps a content type to pdf, docx or other, never to the content type itself")
  void formatIsAClosedSet() {
    assertThat(Format.ofContentType("application/pdf")).isEqualTo(Format.PDF);
    assertThat(
            Format.ofContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .isEqualTo(Format.DOCX);
    assertThat(Format.ofContentType("text/markdown")).isEqualTo(Format.OTHER);
    assertThat(Format.ofContentType("anything; a client could send")).isEqualTo(Format.OTHER);
    assertThat(Format.ofContentType(null)).isEqualTo(Format.OTHER);
  }

  @Test
  @DisplayName("records the size of an accepted version in bytes, by format")
  void recordsUploadSize() {
    meters.uploaded(Format.PDF, 2_000_000);
    meters.uploaded(Format.PDF, 1_000_000);

    DistributionSummary size =
        registry.find(IngestMeters.UPLOAD_SIZE).tag("format", "pdf").summary();
    assertThat(size.count()).isEqualTo(2);
    assertThat(size.totalAmount()).isEqualTo(3_000_000.0);
    assertThat(size.getId().getBaseUnit()).isEqualTo("bytes");
  }
}
