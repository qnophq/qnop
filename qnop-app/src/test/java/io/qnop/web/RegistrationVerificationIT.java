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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.testsupport.SeededIntegrationTest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Self-registration hardening end to end (issues #900/#903): a registration stays pending until the
 * address is verified with the registration password, verification never enables an account an
 * administrator decided about, a colliding registration sends a neutral notice, and every mail of
 * the flow is queued for after the commit. {@link MailService} is mocked like in the other mail ITs
 * (sharing their application context); the verification link is read from the queued mail.
 */
class RegistrationVerificationIT extends SeededIntegrationTest {

  private static final String REGISTER = "/api/v1/auth/register";
  private static final String VERIFY = "/api/v1/auth/verify-email";
  private static final String PASSWORD = "Owner-Pass-1234!";

  @MockitoBean private MailService mail;

  @Autowired private ApplicationSettingsService settings;
  @Autowired private UserRepository users;

  @BeforeEach
  void enableSelfRegistration() {
    settings.update(
        Map.of(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey(), "true"), null);
  }

  @AfterEach
  void restoreSettings() {
    settings.update(
        Map.of(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED.getKey(), "false"), null);
  }

  @Test
  @DisplayName("a registration stays pending until verified with its own password")
  void verificationRequiresTheRegistrationPassword() throws Exception {
    register("newbie", "newbie@qnop.test", PASSWORD).andExpect(status().isOk());

    User pending = internal("newbie");
    assertThat(pending.isEnabled()).isFalse();
    assertThat(pending.isEmailVerificationPending()).isTrue();
    verify(mail, never()).sendMailFromTemplate(any(), any(), anyMap(), any());
    String token = lastVerificationToken("newbie@qnop.test");

    verifyEmail(token, "Some-Other-Pass-1!")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERIFICATION_PASSWORD_MISMATCH"));
    assertThat(internal("newbie").isEnabled()).isFalse();

    // The mistyped attempt did not burn the link.
    verifyEmail(token, PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true));
    User active = internal("newbie");
    assertThat(active.isEnabled()).isTrue();
    assertThat(active.isEmailVerificationPending()).isFalse();

    verifyEmail(token, PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
  }

  @Test
  @DisplayName("a verification link can never enable an administrator-disabled account")
  void verificationCannotEnableAnAdminDisabledAccount() throws Exception {
    register("blocked", "blocked@qnop.test", PASSWORD).andExpect(status().isOk());
    String token = lastVerificationToken("blocked@qnop.test");

    User user = internal("blocked");
    user.setEnabled(false); // the administrator's decision
    users.saveAndFlush(user);

    verifyEmail(token, PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    assertThat(internal("blocked").isEnabled()).isFalse();
  }

  @Test
  @DisplayName(
      "re-registering a pending address replaces the unverified password; only the newest"
          + " registration's link and password activate the account")
  void reRegistrationReplacesAnUnverifiedPassword() throws Exception {
    register("first", "contested@qnop.test", "First-Pass-1234!").andExpect(status().isOk());
    String firstToken = lastVerificationToken("contested@qnop.test");
    clearInvocations(mail);

    register("second", "contested@qnop.test", PASSWORD).andExpect(status().isOk());
    String secondToken = lastVerificationToken("contested@qnop.test");

    verifyEmail(firstToken, "First-Pass-1234!")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    verifyEmail(secondToken, "First-Pass-1234!")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERIFICATION_PASSWORD_MISMATCH"));
    verifyEmail(secondToken, PASSWORD).andExpect(status().isOk());

    assertThat(users.findByUsernameAndSource("first", UserSource.INTERNAL)).isEmpty();
    assertThat(internal("second").isEnabled()).isTrue();
  }

  @Test
  @DisplayName("a registration for a known address sends a neutral notice and changes nothing")
  void duplicateRegistrationSendsANotice() throws Exception {
    User member = users.findById(MEMBER_ID).orElseThrow();
    String hashBefore = member.getPasswordHash();

    register("someone-new", member.getEmail(), PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationRequired").value(true));

    verify(mail)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq(member.getEmail()), anyMap(), any());
    verify(mail, never())
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION), any(), anyMap(), any());
    assertThat(users.findByUsernameAndSource("someone-new", UserSource.INTERNAL)).isEmpty();
    assertThat(users.findById(MEMBER_ID).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
  }

  private User internal(String username) {
    return users.findByUsernameAndSource(username, UserSource.INTERNAL).orElseThrow();
  }

  private String lastVerificationToken(String email) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce())
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION), eq(email), vars.capture(), any());
    List<Map<String, Object>> all = vars.getAllValues();
    String url = all.get(all.size() - 1).get("actionUrl").toString();
    return URLDecoder.decode(url.substring(url.indexOf("token=") + 6), StandardCharsets.UTF_8);
  }

  private ResultActions register(String username, String email, String password) throws Exception {
    return mockMvc.perform(
        post(REGISTER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
                    .formatted(username, email, password)));
  }

  private ResultActions verifyEmail(String token, String password) throws Exception {
    return mockMvc.perform(
        post(VERIFY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
  }
}
