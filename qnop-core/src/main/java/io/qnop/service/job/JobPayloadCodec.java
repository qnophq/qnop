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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * (De)serializes {@link JobPayload}s for the job queue's JSON {@code payload} column (issue #319),
 * through one shared {@link ObjectMapper} so producers and consumers can never diverge. Jackson 3
 * (tools.jackson) matches the stack used elsewhere for the stored jsonb and HTTP models.
 */
public final class JobPayloadCodec {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private JobPayloadCodec() {}

  /** Serializes a payload to its JSON string for {@code JobService.enqueue}. */
  public static String serialize(JobPayload payload) {
    try {
      return MAPPER.writeValueAsString(payload);
    } catch (JacksonException e) {
      // Serializing our own records can only fail on a code bug.
      throw new IllegalStateException("Failed to serialize job payload: " + payload, e);
    }
  }

  /**
   * Reads a stored payload back into its record. A malformed payload can only come from a code bug;
   * failing loudly (the job goes FAILED after its retries) surfaces it rather than dropping work.
   */
  public static <T extends JobPayload> T deserialize(String payload, Class<T> type) {
    try {
      return MAPPER.readValue(payload, type);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed job payload: " + payload, e);
    }
  }
}
