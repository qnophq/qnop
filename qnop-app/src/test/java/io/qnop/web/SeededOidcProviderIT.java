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
package io.qnop.web;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.testsupport.SeededIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * OIDC provider CRUD against the seeded providers (issue #163). Create/update deliberately omit the
 * optional URIs so the SSRF policy and the network-bound discovery endpoint are not exercised here.
 * The client secret is write-only: responses expose only {@code hasClientSecret}.
 */
class SeededOidcProviderIT extends SeededIntegrationTest {

  private static final String PROVIDERS = "/api/v1/admin/oidc-providers";

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + token(ADMIN_ID));
  }

  @Test
  void listsSeededProvidersWithSecretFlags() throws Exception {
    mockMvc
        .perform(asAdmin(get(PROVIDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providers").isArray())
        // The enabled provider carries a secret, the disabled one does not.
        .andExpect(
            jsonPath("$.providers[?(@.name=='Seeded Google')].hasClientSecret")
                .value(hasItem(true)))
        .andExpect(
            jsonPath("$.providers[?(@.name=='Seeded OIDC (disabled)')].hasClientSecret")
                .value(hasItem(false)));
  }

  @Test
  void createsAProviderWithoutEchoingTheSecret() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(PROVIDERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"New IdP\",\"providerType\":\"GITHUB\","
                        + "\"clientId\":\"new-client\",\"clientSecret\":\"new-secret\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("New IdP"))
        .andExpect(jsonPath("$.providerType").value("GITHUB"))
        .andExpect(jsonPath("$.hasClientSecret").value(true))
        .andExpect(jsonPath("$.clientSecret").doesNotExist());
  }

  @Test
  void rejectsADuplicateName() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(PROVIDERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Seeded Google\",\"providerType\":\"OIDC\","
                        + "\"clientId\":\"dup\",\"clientSecret\":\"dup-secret\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NAME_TAKEN"));
  }

  @Test
  void rejectsABlankNameWithAValidationError() throws Exception {
    mockMvc
        .perform(
            asAdmin(post(PROVIDERS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"\",\"providerType\":\"OIDC\","
                        + "\"clientId\":\"x\",\"clientSecret\":\"y\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void getsAProviderById() throws Exception {
    mockMvc
        .perform(asAdmin(get(PROVIDERS + "/" + OIDC_ENABLED_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Seeded Google"))
        .andExpect(jsonPath("$.hasClientSecret").value(true));
  }

  @Test
  void getsAnUnknownProviderAs404() throws Exception {
    mockMvc
        .perform(asAdmin(get(PROVIDERS + "/d0000000-0000-0000-0000-0000000000ff")))
        .andExpect(status().isNotFound());
  }

  @Test
  void disablesAProviderAndKeepsTheSecretWhenOmitted() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(PROVIDERS + "/" + OIDC_ENABLED_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.hasClientSecret").value(true));
  }

  @Test
  void updatingAnUnknownProviderIs404() throws Exception {
    mockMvc
        .perform(
            asAdmin(patch(PROVIDERS + "/d0000000-0000-0000-0000-0000000000ff"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletesAProvider() throws Exception {
    mockMvc
        .perform(asAdmin(delete(PROVIDERS + "/" + OIDC_DISABLED_ID)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(asAdmin(get(PROVIDERS + "/" + OIDC_DISABLED_ID)))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingAnUnknownProviderIs404() throws Exception {
    mockMvc
        .perform(asAdmin(delete(PROVIDERS + "/d0000000-0000-0000-0000-0000000000ff")))
        .andExpect(status().isNotFound());
  }
}
