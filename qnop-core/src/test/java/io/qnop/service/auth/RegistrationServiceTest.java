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
package io.qnop.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.InvalidTextException;
import io.qnop.service.UserService;
import io.qnop.service.auth.EmailVerificationTokenService.InvalidVerificationTokenException;
import io.qnop.service.auth.RegistrationService.VerificationPasswordMismatchException;
import io.qnop.service.mail.MailLocaleResolver;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

  @Mock private UserService userService;
  @Mock private EmailVerificationTokenService verificationTokens;
  @Mock private MailService mailService;
  @Mock private ApplicationSettingsService settings;
  @Mock private MailLocaleResolver mailLocales;
  @Mock private PasswordEncoder passwordEncoder;

  private RegistrationService service;

  @BeforeEach
  void setUp() {
    service =
        new RegistrationService(
            userService, verificationTokens, mailService, settings, mailLocales, passwordEncoder);
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_BASE_URL))
        .thenReturn("https://qnop.example/");
    lenient()
        .when(settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME))
        .thenReturn("qnop");
    lenient().when(mailLocales.forUser(any())).thenReturn("en");
    lenient().when(passwordEncoder.encode("password1")).thenReturn("HASH");
    lenient().when(userService.findInternalByEmail(any())).thenReturn(Optional.empty());
    lenient().when(userService.findInternalByUsername(any())).thenReturn(Optional.empty());
  }

  private static User withId(User user) {
    try {
      Field id = User.class.getDeclaredField("id");
      id.setAccessible(true);
      id.set(user, UUID.randomUUID());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return user;
  }

  @Test
  @DisplayName("a display name with control characters is rejected before anything else")
  void displayNameWithControlCharactersRejected() {
    assertThatThrownBy(
            () -> service.register("jane", "jane@example.com", "password1", "Jane\r\nDoe"))
        .isInstanceOf(InvalidTextException.class);

    verify(userService, never()).findInternalByEmail(any());
    verify(userService, never()).createPendingRegistration(any(), any(), any(), any(), any());
  }

  private static User pending(String username, String email) {
    User user = withId(User.internal(username, email, username, "OLD-HASH"));
    user.markPendingEmailVerification();
    return user;
  }

  private static User active(String username, String email) {
    User user = withId(User.internal(username, email, username, "OLD-HASH"));
    user.setEnabled(true);
    return user;
  }

  private void tokenFor(User user) {
    when(verificationTokens.issue(user))
        .thenReturn(
            new EmailVerificationTokenService.IssuedToken("RAW", Instant.now().plusSeconds(60)));
  }

  @Test
  @DisplayName(
      "a new account is created pending verification with the configured role and the"
          + " verification mail is queued for after the commit")
  void newAccountIsRegistered() {
    User user = pending("jane", "jane@example.com");
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("AUDITOR");
    when(userService.createPendingRegistration(
            "jane", "jane@example.com", "HASH", "Jane", UserRole.AUDITOR))
        .thenReturn(user);
    tokenFor(user);

    service.register("jane", "jane@example.com", "password1", "Jane");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION),
            eq("jane@example.com"),
            vars.capture(),
            eq("en"));
    verify(mailService, never()).sendMailFromTemplate(any(), any(), anyMap(), any());
    // Every placeholder the template uses is supplied (strict Mustache drops the mail otherwise),
    // and the English expiry phrase is unchanged.
    assertThat(vars.getValue().keySet())
        .containsAll(MailTemplateKey.REGISTRATION_VERIFICATION.placeholders());
    assertThat(vars.getValue().get("expiresAtHuman")).isEqualTo("in 1 minute");
    assertThat(vars.getValue().get("actionUrl"))
        .isEqualTo("https://qnop.example/verify-email?token=RAW");
  }

  @Test
  @DisplayName("self-registration never mints an ADMIN, even if the setting says so")
  void adminDefaultRoleIsDowngradedToMember() {
    User user = pending("jane", "jane@example.com");
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("ADMIN");
    when(userService.createPendingRegistration(
            "jane", "jane@example.com", "HASH", "Jane", UserRole.MEMBER))
        .thenReturn(user);
    tokenFor(user);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService)
        .createPendingRegistration("jane", "jane@example.com", "HASH", "Jane", UserRole.MEMBER);
  }

  @Test
  @DisplayName(
      "an address of an active account: nothing is created, the address gets a neutral notice"
          + " in the owner's language, and the password is still encoded (equal cost)")
  void existingActiveAccountGetsNotice() {
    User existing = active("jane", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(mailLocales.forUser(existing.getId())).thenReturn("de");

    service.register("other", "jane@example.com", "password1", "Someone");

    verify(passwordEncoder).encode("password1");
    verify(userService, never()).createPendingRegistration(any(), any(), any(), any(), any());
    verify(userService, never()).replacePendingRegistration(any(), any(), any(), any());
    verify(verificationTokens, never()).issue(any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT),
            eq("jane@example.com"),
            vars.capture(),
            eq("de"));
    assertThat(vars.getValue().keySet())
        .containsAll(MailTemplateKey.REGISTRATION_ATTEMPT.placeholders());
    assertThat(vars.getValue().get("actionUrl")).isEqualTo("https://qnop.example/login");
  }

  @Test
  @DisplayName("an administrator-disabled account is not taken over by a new registration")
  void adminDisabledAccountIsNotTakenOver() {
    User existing = active("jane", "jane@example.com");
    existing.setEnabled(false); // an administrator's decision: not pending
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService, never()).replacePendingRegistration(any(), any(), any(), any());
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq("jane@example.com"), anyMap(), any());
  }

  @Test
  @DisplayName("a taken username with a fresh address: the entered address gets the notice")
  void usernameTakenGetsNotice() {
    User other = active("jane", "jane@example.com");
    when(userService.findInternalByUsername("jane")).thenReturn(Optional.of(other));

    service.register("jane", "fresh@example.com", "password1", "Jane");

    verify(userService, never()).createPendingRegistration(any(), any(), any(), any(), any());
    verify(mailLocales).forUser(null);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq("fresh@example.com"), anyMap(), eq("en"));
  }

  @Test
  @DisplayName(
      "re-registering an address still pending verification replaces the unverified password"
          + " and re-sends the verification mail")
  void pendingAccountIsTakenOver() {
    User existing = pending("squatter", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(userService.replacePendingRegistration(existing.getId(), "jane", "HASH", "Jane"))
        .thenReturn(existing);
    tokenFor(existing);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService).replacePendingRegistration(existing.getId(), "jane", "HASH", "Jane");
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION), eq("jane@example.com"), anyMap(), any());
  }

  @Test
  @DisplayName("a pending address cannot take over a username that belongs to another account")
  void pendingTakeoverRespectsOtherUsernames() {
    User existing = pending("squatter", "jane@example.com");
    User other = active("bob", "bob@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(userService.findInternalByUsername("bob")).thenReturn(Optional.of(other));

    service.register("bob", "jane@example.com", "password1", "Jane");

    verify(userService, never()).replacePendingRegistration(any(), any(), any(), any());
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq("jane@example.com"), anyMap(), any());
  }

  @Test
  @DisplayName("verify with the registration password consumes the token and activates")
  void verifyActivates() {
    User user = pending("jane", "jane@example.com");
    when(verificationTokens.peek("tok")).thenReturn(user);
    when(passwordEncoder.matches("password1", "OLD-HASH")).thenReturn(true);
    when(userService.activatePendingVerification(user.getId())).thenReturn(true);

    service.verify("tok", "password1");

    verify(verificationTokens).consume("tok");
    verify(userService).activatePendingVerification(user.getId());
  }

  @Test
  @DisplayName("verify with another password neither consumes the token nor activates")
  void verifyRejectsWrongPassword() {
    User user = pending("jane", "jane@example.com");
    when(verificationTokens.peek("tok")).thenReturn(user);
    when(passwordEncoder.matches("guess", "OLD-HASH")).thenReturn(false);

    assertThatThrownBy(() -> service.verify("tok", "guess"))
        .isInstanceOf(VerificationPasswordMismatchException.class)
        .satisfies(
            e -> assertThat(((VerificationPasswordMismatchException) e).linkSpent()).isFalse());

    verify(verificationTokens).recordFailedAttempt("tok");
    verify(verificationTokens, never()).consume(any());
    verify(userService, never()).activatePendingVerification(any());
  }

  @Test
  @DisplayName("the wrong password that reaches the attempt cap reports the link as spent")
  void verifyReportsSpentLinkAtTheCap() {
    User user = pending("jane", "jane@example.com");
    when(verificationTokens.peek("tok")).thenReturn(user);
    when(passwordEncoder.matches("guess", "OLD-HASH")).thenReturn(false);
    when(verificationTokens.recordFailedAttempt("tok")).thenReturn(true);

    assertThatThrownBy(() -> service.verify("tok", "guess"))
        .isInstanceOf(VerificationPasswordMismatchException.class)
        .satisfies(
            e -> assertThat(((VerificationPasswordMismatchException) e).linkSpent()).isTrue());
    verify(userService, never()).activatePendingVerification(any());
  }

  @Test
  @DisplayName(
      "within the cool-down a re-registration of a pending address is a silent no-op:"
          + " nothing replaced, no mail")
  void pendingAccountIsProtectedDuringCoolDown() {
    User existing = pending("first", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(verificationTokens.latestIssuedAt(existing.getId()))
        .thenReturn(Optional.of(Instant.now().minusSeconds(60)));

    service.register("second", "jane@example.com", "password1", "Someone");

    verify(passwordEncoder).encode("password1"); // same cost as every other branch
    verify(userService, never()).replacePendingRegistration(any(), any(), any(), any());
    verify(verificationTokens, never()).issue(any());
    verify(mailService, never()).sendMailFromTemplateAfterCommit(any(), any(), anyMap(), any());
  }

  @Test
  @DisplayName("after the cool-down a re-registration of a pending address takes it over")
  void pendingAccountIsTakenOverAfterCoolDown() {
    User existing = pending("first", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(verificationTokens.latestIssuedAt(existing.getId()))
        .thenReturn(
            Optional.of(
                Instant.now().minus(RegistrationService.TAKEOVER_COOL_DOWN).minusSeconds(1)));
    when(userService.replacePendingRegistration(existing.getId(), "second", "HASH", "Someone"))
        .thenReturn(existing);
    tokenFor(existing);

    service.register("second", "jane@example.com", "password1", "Someone");

    verify(userService).replacePendingRegistration(existing.getId(), "second", "HASH", "Someone");
  }

  @Test
  @DisplayName("verify can never enable an administrator-disabled account")
  void verifyRejectsAdminDisabledAccount() {
    User user = active("jane", "jane@example.com");
    user.setEnabled(false);
    when(verificationTokens.peek("tok")).thenReturn(user);

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    verify(verificationTokens, never()).consume(any());
    verify(userService, never()).activatePendingVerification(any());
  }

  @Test
  @DisplayName("verify fails when the account stopped being pending after the checks (race)")
  void verifyFailsWhenActivationLosesTheRace() {
    User user = pending("jane", "jane@example.com");
    when(verificationTokens.peek("tok")).thenReturn(user);
    when(passwordEncoder.matches("password1", "OLD-HASH")).thenReturn(true);
    when(userService.activatePendingVerification(user.getId())).thenReturn(false);

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(InvalidVerificationTokenException.class);
  }
}
