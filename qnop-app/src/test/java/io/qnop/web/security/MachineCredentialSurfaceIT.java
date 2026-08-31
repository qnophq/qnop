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
package io.qnop.web.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.spi.auth.BearerCredentialAuthenticator;
import io.qnop.spi.auth.MachinePrincipal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the machine-reachable surface (issue #686, ADR-0060): a contributed machine principal is
 * authenticated but nearly powerless — it passes {@code .authenticated()}, so exactly the endpoints
 * gated by neither a role nor {@code requireUserId()} answer it. Today that residue is the banner
 * read. If this test fails because a new endpoint joined the reachable surface, that join must be a
 * deliberate decision, not an accident.
 */
@AutoConfigureMockMvc
@Import(MachineCredentialSurfaceIT.TestAuthenticator.class)
class MachineCredentialSurfaceIT extends AbstractIntegrationTest {

  private static final String MACHINE_KEY = "svc-test-key-for-surface-pinning";

  @TestConfiguration
  static class TestAuthenticator {
    @Bean
    BearerCredentialAuthenticator surfacePinningAuthenticator() {
      return credential ->
          MACHINE_KEY.equals(credential)
              ? Optional.of(new MachinePrincipal("svc:surface-pin", Set.of("reviews.read")))
              : Optional.empty();
    }
  }

  @Autowired MockMvc mockMvc;

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asMachine(
      String path) {
    return get(path).header("Authorization", "Bearer " + MACHINE_KEY);
  }

  @Test
  void theResidueIsExactlyTheRoleLessUserLessEndpoints() throws Exception {
    // Authenticated: the machine principal exists in the eyes of the chain.
    mockMvc.perform(asMachine("/api/v1/banner")).andExpect(status().isOk());

    // User-actor endpoints refuse it (requireUserId -> 403)...
    mockMvc.perform(asMachine("/api/v1/documents")).andExpect(status().isForbidden());
    // ...role gates refuse it (EXT_ authorities are never ROLE_*)...
    mockMvc.perform(asMachine("/api/v1/admin/settings")).andExpect(status().isForbidden());
    mockMvc.perform(asMachine("/api/v1/audit/events")).andExpect(status().isForbidden());
    // ...and the auth surface treats it as no user session at all (401, see AuthController).
    mockMvc.perform(asMachine("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void anUnclaimedCredentialStaysTheStandard401() throws Exception {
    mockMvc
        .perform(get("/api/v1/banner").header("Authorization", "Bearer not-anyones-key"))
        .andExpect(status().isUnauthorized());
  }
}
