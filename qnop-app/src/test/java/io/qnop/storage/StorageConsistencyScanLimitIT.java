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
package io.qnop.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.spi.storage.StorageProvider;
import io.qnop.testsupport.SeededIntegrationTest;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The scan circuit breaker over the wire (issue #523, ADR-0044): with the object limit forced to 1,
 * a bucket holding more than one object aborts the scan with HTTP 409 and the stable code, rather
 * than streaming forever.
 */
@TestPropertySource(properties = "qnop.s3.consistency-scan-max-keys=1")
class StorageConsistencyScanLimitIT extends SeededIntegrationTest {

  private static final String SCAN = "/api/v1/admin/storage-consistency";

  @Autowired private StorageProvider provider;

  private void putObject() throws Exception {
    byte[] content = ("limit-" + UUID.randomUUID()).getBytes(UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    provider.put(
        "sha256/" + hash.substring(0, 2) + "/" + hash,
        new ByteArrayInputStream(content),
        content.length,
        "application/pdf");
  }

  @Test
  @DisplayName("aborts with 409 STORAGE_SCAN_LIMIT once the bucket exceeds the object limit")
  void scanAbortsWith409() throws Exception {
    putObject();
    putObject();

    mockMvc
        .perform(get(SCAN).header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STORAGE_SCAN_LIMIT"));
  }
}
