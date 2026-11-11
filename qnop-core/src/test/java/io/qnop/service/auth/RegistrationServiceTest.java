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
import io.qnop.service.UserService.ActivationOutcome;
import io.qnop.service.auth.EmailVerificationTokenService.InvalidVerificationTokenException;
import io.qnop.service.auth.EmailVerificationTokenService.PendingVerification;
import io.qnop.service.auth.RegistrationService.VerificationPasswordMismatchException;
import io.qnop.service.auth.RegistrationService.VerificationUsernameTakenException;
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
    lenient()
        .when(verificationTokens.issueForRegistration(any(), any(), any(), any()))
        .thenReturn(
            new EmailVerificationTokenService.IssuedToken("RAW", Instant.now().plusSeconds(60)));
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

  private static User pending(String username, String email) {
    User user = withId(User.internal(username, email, username, "ROW-HASH"));
    user.markPendingEmailVerification();
    return user;
  }

  private static User active(String username, String email) {
    User user = withId(User.internal(username, email, username, "ROW-HASH"));
    user.setEnabled(true);
    return user;
  }

  private static PendingVerification pendingVerification(UUID userId, boolean accountPending) {
    return new PendingVerification(
        UUID.randomUUID(), userId, accountPending, "jane", "Jane", "CAND-HASH");
  }

  @Test
  @DisplayName("a display name with control characters is rejected before anything else")
  void displayNameWithControlCharactersRejected() {
    assertThatThrownBy(
            () -> service.register("jane", "jane@example.com", "password1", "Jane\r\nDoe"))
        .isInstanceOf(InvalidTextException.class);

    verify(userService, never()).findInternalByEmail(any());
    verify(userService, never()).createPendingRegistration(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "a new account is created pending verification with the configured role; its link carries"
          + " the registration's credentials and the mail is queued for after the commit")
  void newAccountIsRegistered() {
    User user = pending("jane", "jane@example.com");
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("AUDITOR");
    when(userService.createPendingRegistration(
            "jane", "jane@example.com", "Jane", UserRole.AUDITOR))
        .thenReturn(user);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(verificationTokens).issueForRegistration(user, "jane", "Jane", "HASH");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION),
            eq("jane@example.com"),
            vars.capture(),
            eq("en"));
    verify(mailService, never()).sendMailFromTemplate(any(), any(), anyMap(), any());
    assertThat(vars.getValue().keySet())
        .containsAll(MailTemplateKey.REGISTRATION_VERIFICATION.placeholders());
    assertThat(vars.getValue().get("expiresAtHuman")).isEqualTo("in 1 minute");
    assertThat(vars.getValue().get("actionUrl"))
        .isEqualTo("https://qnop.example/verify-email?token=RAW");
    // The greeting is the address's own local part, never a name from the request.
    assertThat(vars.getValue().get("recipientName")).isEqualTo("jane");
  }

  @Test
  @DisplayName("self-registration never mints an ADMIN, even if the setting says so")
  void adminDefaultRoleIsDowngradedToMember() {
    User user = pending("jane", "jane@example.com");
    when(settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE))
        .thenReturn("ADMIN");
    when(userService.createPendingRegistration("jane", "jane@example.com", "Jane", UserRole.MEMBER))
        .thenReturn(user);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(userService)
        .createPendingRegistration("jane", "jane@example.com", "Jane", UserRole.MEMBER);
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
    verify(userService, never()).createPendingRegistration(any(), any(), any(), any());
    verify(verificationTokens, never()).issueForRegistration(any(), any(), any(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT),
            eq("jane@example.com"),
            vars.capture(),
            eq("de"));
    // No text from the request reaches the notice.
    assertThat(vars.getValue())
        .containsOnlyKeys("siteName", "actionUrl")
        .containsEntry("actionUrl", "https://qnop.example/login");
  }

  @Test
  @DisplayName("an administrator-disabled account gets no new link, only the notice")
  void adminDisabledAccountGetsNoLink() {
    User existing = active("jane", "jane@example.com");
    existing.setEnabled(false); // an administrator's decision: not pending
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(verificationTokens, never()).issueForRegistration(any(), any(), any(), any());
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

    verify(userService, never()).createPendingRegistration(any(), any(), any(), any());
    verify(mailLocales).forUser(null);
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq("fresh@example.com"), anyMap(), eq("en"));
  }

  @Test
  @DisplayName(
      "registering a pending address issues one more link with this registration's credentials"
          + " and changes nothing else — no cool-down, nothing invalidated")
  void pendingAddressGetsAnotherLink() {
    User existing = pending("squatter", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));

    service.register("jane", "jane@example.com", "password1", null);

    verify(userService).lockForUpdate(existing.getId());
    verify(verificationTokens).issueForRegistration(existing, "jane", "jane", "HASH");
    verify(userService, never()).createPendingRegistration(any(), any(), any(), any());
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_VERIFICATION), eq("jane@example.com"), anyMap(), any());
  }

  @Test
  @DisplayName(
      "within the issuance throttle a registration of a pending address is a silent no-op:"
          + " no link, no mail, but the same password-hashing cost")
  void pendingAddressIsThrottled() {
    User existing = pending("squatter", "jane@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(verificationTokens.issuedWithin(existing.getId(), RegistrationService.ISSUE_THROTTLE))
        .thenReturn(true);

    service.register("jane", "jane@example.com", "password1", "Jane");

    verify(passwordEncoder).encode("password1");
    verify(verificationTokens, never()).issueForRegistration(any(), any(), any(), any());
    verify(mailService, never()).sendMailFromTemplateAfterCommit(any(), any(), anyMap(), any());
  }

  @Test
  @DisplayName("a pending address cannot claim a username that belongs to another account")
  void pendingAddressRespectsOtherUsernames() {
    User existing = pending("squatter", "jane@example.com");
    User other = active("bob", "bob@example.com");
    when(userService.findInternalByEmail("jane@example.com")).thenReturn(Optional.of(existing));
    when(userService.findInternalByUsername("bob")).thenReturn(Optional.of(other));

    service.register("bob", "jane@example.com", "password1", "Jane");

    verify(verificationTokens, never()).issueForRegistration(any(), any(), any(), any());
    verify(mailService)
        .sendMailFromTemplateAfterCommit(
            eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq("jane@example.com"), anyMap(), any());
  }

  @Test
  @DisplayName(
      "verify reserves an attempt, checks the token's own password, consumes it, activates with"
          + " the token's credentials and spends the other links")
  void verifyActivatesWithTheTokensCredentials() {
    UUID userId = UUID.randomUUID();
    PendingVerification pending = pendingVerification(userId, true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(true);
    when(passwordEncoder.matches("password1", "CAND-HASH")).thenReturn(true);
    when(userService.activateVerifiedRegistration(userId, "jane", "Jane", "CAND-HASH"))
        .thenReturn(ActivationOutcome.ACTIVATED);

    service.verify("tok", "password1");

    var order = org.mockito.Mockito.inOrder(verificationTokens, passwordEncoder, userService);
    order.verify(userService).lockForUpdate(userId);
    order.verify(verificationTokens).reserveAttempt(pending.tokenId());
    order.verify(passwordEncoder).matches("password1", "CAND-HASH");
    order.verify(userService).activateVerifiedRegistration(userId, "jane", "Jane", "CAND-HASH");
    order.verify(verificationTokens).consume(pending.tokenId());
    order.verify(verificationTokens).consumeAllForUser(userId);
  }

  @Test
  @DisplayName("a username taken meanwhile spends the link and reports its own outcome")
  void verifySpendsTheLinkWhenTheUsernameIsTaken() {
    UUID userId = UUID.randomUUID();
    PendingVerification pending = pendingVerification(userId, true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(true);
    when(passwordEncoder.matches("password1", "CAND-HASH")).thenReturn(true);
    when(userService.activateVerifiedRegistration(userId, "jane", "Jane", "CAND-HASH"))
        .thenReturn(ActivationOutcome.USERNAME_TAKEN);

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(VerificationUsernameTakenException.class);

    verify(verificationTokens).consume(pending.tokenId());
    verify(verificationTokens, never()).consumeAllForUser(any());
  }

  @Test
  @DisplayName("without an attempt left, no password is compared at all")
  void verifyComparesNothingWhenAttemptsAreGone() {
    PendingVerification pending = pendingVerification(UUID.randomUUID(), true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(false);

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    verify(passwordEncoder, never()).matches(any(), any());
    verify(userService, never()).activateVerifiedRegistration(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a wrong password neither consumes the token nor activates")
  void verifyRejectsWrongPassword() {
    PendingVerification pending = pendingVerification(UUID.randomUUID(), true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(true);
    when(passwordEncoder.matches("guess", "CAND-HASH")).thenReturn(false);

    assertThatThrownBy(() -> service.verify("tok", "guess"))
        .isInstanceOf(VerificationPasswordMismatchException.class)
        .satisfies(
            e -> assertThat(((VerificationPasswordMismatchException) e).linkSpent()).isFalse());

    verify(verificationTokens, never()).consume(any());
    verify(userService, never()).activateVerifiedRegistration(any(), any(), any(), any());
  }

  @Test
  @DisplayName("the wrong password that uses up the allowance reports the link as spent")
  void verifyReportsSpentLinkAtTheCap() {
    PendingVerification pending = pendingVerification(UUID.randomUUID(), true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(true);
    when(passwordEncoder.matches("guess", "CAND-HASH")).thenReturn(false);
    when(verificationTokens.spendIfExhausted(pending.tokenId())).thenReturn(true);

    assertThatThrownBy(() -> service.verify("tok", "guess"))
        .isInstanceOf(VerificationPasswordMismatchException.class)
        .satisfies(
            e -> assertThat(((VerificationPasswordMismatchException) e).linkSpent()).isTrue());
  }

  @Test
  @DisplayName("verify can never enable an administrator-disabled account")
  void verifyRejectsAdminDisabledAccount() {
    when(verificationTokens.peek("tok")).thenReturn(pendingVerification(UUID.randomUUID(), false));

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    verify(verificationTokens, never()).reserveAttempt(any());
    verify(userService, never()).activateVerifiedRegistration(any(), any(), any(), any());
  }

  @Test
  @DisplayName("verify fails when the account stopped being pending after the checks (race)")
  void verifyFailsWhenActivationLosesTheRace() {
    UUID userId = UUID.randomUUID();
    PendingVerification pending = pendingVerification(userId, true);
    when(verificationTokens.peek("tok")).thenReturn(pending);
    when(verificationTokens.reserveAttempt(pending.tokenId())).thenReturn(true);
    when(passwordEncoder.matches("password1", "CAND-HASH")).thenReturn(true);
    when(userService.activateVerifiedRegistration(userId, "jane", "Jane", "CAND-HASH"))
        .thenReturn(ActivationOutcome.NOT_PENDING);

    assertThatThrownBy(() -> service.verify("tok", "password1"))
        .isInstanceOf(InvalidVerificationTokenException.class);
    verify(verificationTokens, never()).consumeAllForUser(any());
  }
}
