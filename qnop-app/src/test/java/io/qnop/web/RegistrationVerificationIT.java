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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
  @DisplayName("a registration stays pending until verified; a mistyped password keeps the link")
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
      "squatter and owner both register a pending address: each link keeps its own credentials,"
          + " the owner verifies theirs, the owner's password is active and the squatter's link is"
          + " dead")
  void ownerWinsOverASquatterRegardlessOfOrder() throws Exception {
    register("squatter", "contested@qnop.test", "Squat-Pass-1234!").andExpect(status().isOk());
    String squatterToken = lastVerificationToken("contested@qnop.test");
    clearInvocations(mail);

    // After the issuance throttle, the owner's registration gets a link of its own; the
    // squatter's link stays as it is.
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    register("owner", "contested@qnop.test", PASSWORD).andExpect(status().isOk());
    String ownerToken = lastVerificationToken("contested@qnop.test");

    // A later registration by the squatter does not kill the owner's link either.
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    register("squatter", "contested@qnop.test", "Squat-Pass-1234!").andExpect(status().isOk());

    // Each link checks its own registration's password.
    verifyEmail(ownerToken, "Squat-Pass-1234!")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERIFICATION_PASSWORD_MISMATCH"));
    verifyEmail(ownerToken, PASSWORD).andExpect(status().isOk());

    User account =
        users
            .findByEmailIgnoreCaseAndSource("contested@qnop.test", UserSource.INTERNAL)
            .orElseThrow();
    assertThat(account.isEnabled()).isTrue();
    assertThat(account.getUsername()).isEqualTo("owner");
    assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
    assertThat(passwordEncoder.matches("Squat-Pass-1234!", account.getPasswordHash())).isFalse();

    // Every other link of the address is spent.
    verifyEmail(squatterToken, "Squat-Pass-1234!")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
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

  @Test
  @DisplayName("the database refuses an account that is both pending and enabled")
  void pendingAndEnabledIsRejectedByTheSchema() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE qnop_user SET enabled = TRUE, email_verification_pending = TRUE"
                        + " WHERE id = ?",
                    MEMBER_ID))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName(
      "the 0039 backfill marks only untouched, never-verified registrations with a live token")
  void backfillIsConservative() throws Exception {
    java.util.UUID untouched = disabledWithToken("bf-untouched", false, false, false);
    java.util.UUID edited = disabledWithToken("bf-edited", true, false, false);
    java.util.UUID loggedIn = disabledWithToken("bf-logged-in", false, true, false);
    java.util.UUID consumedOnly = disabledWithToken("bf-consumed", false, false, true);

    jdbc.update(backfillStatement());

    assertThat(pendingFlag(untouched)).isTrue();
    assertThat(pendingFlag(edited)).isFalse();
    assertThat(pendingFlag(loggedIn)).isFalse();
    assertThat(pendingFlag(consumedOnly)).isFalse();
    // Enabled seed accounts are never touched.
    assertThat(pendingFlag(MEMBER_ID)).isFalse();
  }

  private java.util.UUID disabledWithToken(
      String username, boolean edited, boolean loggedIn, boolean consumed) {
    User user = User.internal(username, username + "@qnop.test", username, "h");
    user.setEnabled(false);
    user.setSlug(username);
    User saved = users.saveAndFlush(user);
    jdbc.update(
        "INSERT INTO email_verification_token (id, user_id, token_hash, created_at, expires_at,"
            + " consumed_at) VALUES (?, ?, ?, now(), now() + interval '1 day', "
            + (consumed ? "now()" : "NULL")
            + ")",
        java.util.UUID.randomUUID(),
        saved.getId(),
        (username + "0000000000000000000000000000000000000000000000000000000000000000")
            .substring(0, 64));
    if (edited) {
      jdbc.update("UPDATE qnop_user SET version = version + 1 WHERE id = ?", saved.getId());
    }
    if (loggedIn) {
      jdbc.update("UPDATE qnop_user SET last_login_at = now() WHERE id = ?", saved.getId());
    }
    return saved.getId();
  }

  private boolean pendingFlag(java.util.UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT email_verification_pending FROM qnop_user WHERE id = ?", Boolean.class, id));
  }

  /** The backfill UPDATE exactly as shipped in the 0039 changelog. */
  private static String backfillStatement() throws java.io.IOException {
    String yaml;
    try (java.io.InputStream in =
        RegistrationVerificationIT.class.getResourceAsStream(
            "/db/changelog/migrations/0039-user-email-verification-pending.yaml")) {
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int start = yaml.indexOf("UPDATE qnop_user");
    return yaml.substring(start, yaml.indexOf(';', start));
  }

  @Test
  @DisplayName(
      "the per-address throttle and the three-link cap: a displaced link takes three windows, and"
          + " the owner recovers by registering again")
  void linkEvictionIsThrottledAndRecoverable() throws Exception {
    register("evictee", "evictee@qnop.test", PASSWORD).andExpect(status().isOk());
    String ownerToken = lastVerificationToken("evictee@qnop.test");
    clearInvocations(mail);

    // Within the throttle window: no new link, no mail.
    register("evictee", "evictee@qnop.test", "Other-Pass-1234!").andExpect(status().isOk());
    verify(mail, never()).sendMailFromTemplateAfterCommit(any(), any(), anyMap(), any());

    // Two more registrations, each after a window: three links outstanding, the owner's alive.
    for (int i = 0; i < 2; i++) {
      ageVerificationTokens(java.time.Duration.ofMinutes(6));
      register("evictee", "evictee@qnop.test", "Other-Pass-1234!").andExpect(status().isOk());
    }
    assertThat(outstandingLinks("evictee@qnop.test")).isEqualTo(3);

    // The third one after the owner's link displaces it (the oldest).
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    register("evictee", "evictee@qnop.test", "Other-Pass-1234!").andExpect(status().isOk());
    assertThat(outstandingLinks("evictee@qnop.test")).isEqualTo(3);
    verifyEmail(ownerToken, PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

    // Recovery: register again and verify the new link.
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    clearInvocations(mail);
    register("evictee", "evictee@qnop.test", PASSWORD).andExpect(status().isOk());
    verifyEmail(lastVerificationToken("evictee@qnop.test"), PASSWORD).andExpect(status().isOk());
    assertThat(internal("evictee").isEnabled()).isTrue();
  }

  @Test
  @DisplayName(
      "a registration whose username another account took meanwhile cannot be confirmed; the link"
          + " stays usable and its holder sets another username")
  void usernameTakenMeanwhileKeepsTheLink() throws Exception {
    register("first-name", "late@qnop.test", "First-Pass-1234!").andExpect(status().isOk());
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    clearInvocations(mail);
    register("wanted", "late@qnop.test", PASSWORD).andExpect(status().isOk());
    String token = lastVerificationToken("late@qnop.test");

    // Candidate usernames reserve nothing: another address registers "wanted" in the meantime.
    register("wanted", "someone-else@qnop.test", "Else-Pass-1234!").andExpect(status().isOk());

    verifyEmail(token, PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERIFICATION_USERNAME_TAKEN"));
    setNewCredentials(token, PASSWORD, "wanted-too").andExpect(status().isOk());

    User account =
        users.findByEmailIgnoreCaseAndSource("late@qnop.test", UserSource.INTERNAL).orElseThrow();
    assertThat(account.isEnabled()).isTrue();
    assertThat(account.getUsername()).isEqualTo("wanted-too");
  }

  @Test
  @DisplayName(
      "the owner of an address claims it with a link somebody else's registration triggered:"
          + " own password, own username — the throttle never stands in the way")
  void ownerClaimsTheAccountWithASquatterTriggeredLink() throws Exception {
    // Only the squatter registers, repeatedly; the owner never gets a link of their own.
    register("squatter", "claimed@qnop.test", "Squat-Pass-1234!").andExpect(status().isOk());
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    register("squatter", "claimed@qnop.test", "Squat-Pass-1234!").andExpect(status().isOk());
    String squatterTriggered = lastVerificationToken("claimed@qnop.test");
    clearInvocations(mail);

    // The owner's own registration falls into the throttle window: no new mail ...
    register("owner", "claimed@qnop.test", PASSWORD).andExpect(status().isOk());
    verify(mail, never()).sendMailFromTemplateAfterCommit(any(), any(), anyMap(), any());

    // ... but the link already in the mailbox is enough: the owner sets their own credentials.
    verifyEmail(squatterTriggered, PASSWORD)
        .andExpect(jsonPath("$.code").value("VERIFICATION_PASSWORD_MISMATCH"));
    setNewCredentials(squatterTriggered, PASSWORD, "owner").andExpect(status().isOk());

    User account =
        users
            .findByEmailIgnoreCaseAndSource("claimed@qnop.test", UserSource.INTERNAL)
            .orElseThrow();
    assertThat(account.isEnabled()).isTrue();
    assertThat(account.getUsername()).isEqualTo("owner");
    assertThat(account.getDisplayName()).isEqualTo("owner");
    assertThat(passwordEncoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
    assertThat(passwordEncoder.matches("Squat-Pass-1234!", account.getPasswordHash())).isFalse();
    assertThat(outstandingLinks("claimed@qnop.test")).isZero();
  }

  @Test
  @DisplayName("new credentials follow the registration policy")
  void setNewEnforcesThePolicy() throws Exception {
    register("policy", "policy@qnop.test", PASSWORD).andExpect(status().isOk());
    String token = lastVerificationToken("policy@qnop.test");

    setNewCredentials(token, "short", "policy")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(internal("policy").isEnabled()).isFalse();
  }

  @Test
  @DisplayName("a link issued before credentials moved to the token still verifies against the row")
  void legacyTokenStillVerifies() throws Exception {
    register("legacy", "legacy@qnop.test", "Ignored-Pass-1234!").andExpect(status().isOk());
    User account = internal("legacy");
    // What a pre-upgrade registration looks like: the password on the row, a token without
    // candidate credentials.
    jdbc.update(
        "UPDATE qnop_user SET password_hash = ? WHERE id = ?",
        passwordEncoder.encode(PASSWORD),
        account.getId());
    jdbc.update(
        "UPDATE email_verification_token SET consumed_at = now(), candidate_username = NULL,"
            + " candidate_display_name = NULL, candidate_password_hash = NULL WHERE user_id = ?",
        account.getId());
    String raw = "legacy-raw-token-value";
    jdbc.update(
        "INSERT INTO email_verification_token (id, user_id, token_hash, created_at, expires_at)"
            + " VALUES (?, ?, ?, now(), now() + interval '1 day')",
        java.util.UUID.randomUUID(),
        account.getId(),
        sha256Hex(raw));

    verifyEmail(raw, PASSWORD).andExpect(status().isOk());
    User active = internal("legacy");
    assertThat(active.isEnabled()).isTrue();
    assertThat(passwordEncoder.matches(PASSWORD, active.getPasswordHash())).isTrue();
  }

  @Test
  @DisplayName("an admin cannot enable an account that is still pending verification (409)")
  void adminEnableOfAPendingAccountIsRefused() throws Exception {
    register("waiting", "waiting@qnop.test", PASSWORD).andExpect(status().isOk());
    User account = internal("waiting");

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/admin/users/" + account.getId())
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_PENDING_VERIFICATION"));
    assertThat(internal("waiting").isEnabled()).isFalse();
    assertThat(internal("waiting").isEmailVerificationPending()).isTrue();
  }

  private int outstandingLinks(String email) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM email_verification_token t JOIN qnop_user u ON u.id = t.user_id"
            + " WHERE lower(u.email) = lower(?) AND t.consumed_at IS NULL",
        Integer.class,
        email);
  }

  private void ageVerificationTokens(java.time.Duration age) {
    jdbc.update(
        "UPDATE email_verification_token SET created_at = created_at - (? * interval '1 second')",
        age.toSeconds());
  }

  private static String sha256Hex(String input) throws Exception {
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.getBytes(StandardCharsets.UTF_8));
    return java.util.HexFormat.of().formatHex(digest);
  }

  @Test
  @DisplayName(
      "after activation neither the used link nor any other link of the address works, and an"
          + " unknown token is rejected the same way")
  void linksCannotBeReplayed() throws Exception {
    register("replay", "replay@qnop.test", PASSWORD).andExpect(status().isOk());
    String first = lastVerificationToken("replay@qnop.test");
    ageVerificationTokens(java.time.Duration.ofMinutes(6));
    clearInvocations(mail);
    register("replay", "replay@qnop.test", PASSWORD).andExpect(status().isOk());
    String second = lastVerificationToken("replay@qnop.test");

    verifyEmail(second, PASSWORD).andExpect(status().isOk());

    for (String link : List.of(first, second)) {
      verifyEmail(link, PASSWORD)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
      setNewCredentials(link, "Other-Pass-1234!", "replayer")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    }
    verifyEmail("not-a-token", PASSWORD)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    assertThat(internal("replay").getUsername()).isEqualTo("replay");
  }

  @Test
  @DisplayName(
      "a link older than the password-reset lifetime can still confirm but no longer set new"
          + " credentials")
  void setNewOnlyWithinTheResetLinkLifetime() throws Exception {
    register("aged", "aged@qnop.test", PASSWORD).andExpect(status().isOk());
    String token = lastVerificationToken("aged@qnop.test");
    // Default reset-link lifetime is 30 minutes.
    ageVerificationTokens(java.time.Duration.ofMinutes(31));

    setNewCredentials(token, "Other-Pass-1234!", "other")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERIFICATION_SET_NEW_EXPIRED"));
    verifyEmail(token, PASSWORD).andExpect(status().isOk());
    assertThat(internal("aged").isEnabled()).isTrue();
  }

  @Test
  @DisplayName(
      "a password beyond 72 UTF-8 bytes is a validation error on every path: register, set new,"
          + " reset and change")
  void oversizedPasswordsAreValidationErrors() throws Exception {
    String oversized = "ä".repeat(36) + "x"; // 37 characters, 73 bytes

    register("bytes", "bytes@qnop.test", oversized)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    register("bytes", "bytes@qnop.test", PASSWORD).andExpect(status().isOk());
    setNewCredentials(lastVerificationToken("bytes@qnop.test"), oversized, "bytes")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"whatever\",\"newPassword\":\"%s\"}".formatted(oversized)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + token(MEMBER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(SEED_PASSWORD, oversized)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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

  private ResultActions setNewCredentials(String token, String password, String username)
      throws Exception {
    return mockMvc.perform(
        post(VERIFY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"token\":\"%s\",\"password\":\"%s\",\"setNew\":true,\"username\":\"%s\"}"
                    .formatted(token, password, username)));
  }

  private ResultActions verifyEmail(String token, String password) throws Exception {
    return mockMvc.perform(
        post(VERIFY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
  }
}
