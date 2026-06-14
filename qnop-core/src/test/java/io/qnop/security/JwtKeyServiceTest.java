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
package io.qnop.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** Verifies that derived JWT keys are deterministic, domain-separated, and well-formed. */
class JwtKeyServiceTest {

  private static JwtKeyService service() {
    QnopProperties properties =
        new QnopProperties(
            new QnopProperties.Auth(
                "jwt-key-service-test-secret-0123456789",
                "jwt-key-service-test-enckey-0123456789",
                "0123456789abcdef0123456789abcdef",
                null,
                null,
                null,
                null),
            new QnopProperties.Cors(List.of()));
    return new JwtKeyService(properties);
  }

  @Test
  void derivationIsDeterministic() {
    JwtKeyService service = service();

    assertArrayEquals(
        service.deriveKey("access-token").getEncoded(),
        service.deriveKey("access-token").getEncoded());
  }

  @Test
  void differentPurposesYieldDifferentKeys() {
    JwtKeyService service = service();

    assertFalse(
        Arrays.equals(
            service.deriveKey("access-token").getEncoded(),
            service.deriveKey("refresh-token").getEncoded()));
  }

  @Test
  void keyIs256BitHmacSha256() {
    SecretKey key = service().deriveKey("access-token");

    assertEquals(32, key.getEncoded().length);
    assertEquals("HmacSHA256", key.getAlgorithm());
  }
}
