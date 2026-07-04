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
package io.qnop.service.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.service.job.JobPayload.DocumentVersionRef;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared job-payload (de)serialization (issue #319). */
class JobPayloadCodecTest {

  private final UUID versionId = UUID.fromString("019f2a07-67a1-7d19-a114-6d602897729d");

  @Test
  @DisplayName("serializes a version-ref payload to the compact {\"versionId\":...} shape")
  void serializesToTheStableShape() {
    String json = JobPayloadCodec.serialize(new DocumentVersionRef(versionId));

    assertThat(json).isEqualTo("{\"versionId\":\"" + versionId + "\"}");
  }

  @Test
  @DisplayName("round-trips a payload through serialize/deserialize")
  void roundTrips() {
    DocumentVersionRef original = new DocumentVersionRef(versionId);

    String json = JobPayloadCodec.serialize(original);
    DocumentVersionRef back = JobPayloadCodec.deserialize(json, DocumentVersionRef.class);

    assertThat(back).isEqualTo(original);
    assertThat(back.versionId()).isEqualTo(versionId);
  }

  @Test
  @DisplayName("reads a payload written by the previous hand-built format (backward compatible)")
  void readsLegacyHandBuiltPayload() {
    String legacy = "{\"versionId\":\"" + versionId + "\"}";

    DocumentVersionRef payload = JobPayloadCodec.deserialize(legacy, DocumentVersionRef.class);

    assertThat(payload.versionId()).isEqualTo(versionId);
  }

  @Test
  @DisplayName("rejects a malformed payload with a clear IllegalArgumentException")
  void rejectsMalformedPayload() {
    assertThatThrownBy(() -> JobPayloadCodec.deserialize("not json", DocumentVersionRef.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed job payload");
  }

  @Test
  @DisplayName("rejects a payload with a missing versionId")
  void rejectsMissingVersionId() {
    assertThatThrownBy(() -> JobPayloadCodec.deserialize("{}", DocumentVersionRef.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed job payload");
  }

  @Test
  @DisplayName("rejects a payload whose versionId is not a UUID")
  void rejectsNonUuidVersionId() {
    assertThatThrownBy(
            () -> JobPayloadCodec.deserialize("{\"versionId\":\"nope\"}", DocumentVersionRef.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed job payload");
  }
}
