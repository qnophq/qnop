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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.qnop.bootstrap.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end proof that the OIDC/OAuth2 {@code state} is validated against the stored authorization
 * request (issue #321): a callback with a forged or absent {@code state} — i.e. no matching
 * authorization request was ever initiated — is rejected by Spring Security's {@code
 * OAuth2LoginAuthenticationFilter} before {@link OidcLoginSuccessHandler} can run, so no qnop
 * session (refresh cookie) is ever minted and the browser is bounced to the login error page.
 * Requires Docker.
 */
@AutoConfigureMockMvc
class OidcStateValidationIT extends AbstractIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  @DisplayName("a forged state (no initiated authorization request) mints no session")
  void forgedStateIsRejected() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/login/oauth2/code/{registrationId}", UUID.randomUUID())
                    .param("code", "forged-authorization-code")
                    .param("state", "forged-state-value"))
            .andReturn();

    assertRejected(result);
  }

  @Test
  @DisplayName("an absent state is rejected the same way")
  void absentStateIsRejected() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/login/oauth2/code/{registrationId}", UUID.randomUUID())
                    .param("code", "forged-authorization-code"))
            .andReturn();

    assertRejected(result);
  }

  /**
   * The security property: the callback did not authenticate. The success handler is the sole
   * issuer of the {@code qnop_refresh} cookie on this path, so its absence proves the handler never
   * ran; the redirect lands on the login error page, not the SPA success target.
   */
  private static void assertRejected(MvcResult result) {
    assertThat(result.getResponse().getCookie(RefreshTokenCookieFactory.COOKIE_NAME)).isNull();
    assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
    String redirect = result.getResponse().getRedirectedUrl();
    assertThat(redirect).isNotNull();
    assertThat(redirect).contains("error");
  }
}
