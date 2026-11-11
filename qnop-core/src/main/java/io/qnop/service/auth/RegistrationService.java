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

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.InvalidTextException;
import io.qnop.service.UserService;
import io.qnop.service.auth.EmailVerificationTokenService.InvalidVerificationTokenException;
import io.qnop.service.mail.MailLocaleResolver;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.RelativeTimePhrase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates self-registration and email verification (issue #20), keeping the {@code User}
 * entity inside the service layer (ADR-0004). The controller stays thin and never touches an
 * entity.
 *
 * <p>Hardening (issues #900/#903, ADR-0026 amendment):
 *
 * <ul>
 *   <li>A new account is <em>pending verification</em> — a state of its own, distinct from an
 *       administrator-disabled account — and {@link #verify} activates only a pending account.
 *   <li>Verification requires the password chosen at registration, so a password only becomes a
 *       credential once the mailbox owner, who receives the link, confirms it. Whoever registered
 *       an address they do not control gains nothing: the owner cannot confirm a password they do
 *       not know, and their own registration of that address replaces the unverified one.
 *   <li>A registration that collides with an existing account sends a neutral notice to the entered
 *       address instead of nothing; the response stays uniform either way.
 *   <li>Every branch encodes the password once and every mail is sent after commit off the request
 *       thread, so the response time does not tell the branches apart.
 * </ul>
 */
@Service
public class RegistrationService {

  private final UserService userService;
  private final EmailVerificationTokenService verificationTokens;
  private final MailService mailService;
  private final ApplicationSettingsService settings;
  private final MailLocaleResolver mailLocales;
  private final PasswordEncoder passwordEncoder;

  public RegistrationService(
      UserService userService,
      EmailVerificationTokenService verificationTokens,
      MailService mailService,
      ApplicationSettingsService settings,
      MailLocaleResolver mailLocales,
      PasswordEncoder passwordEncoder) {
    this.userService = userService;
    this.verificationTokens = verificationTokens;
    this.mailService = mailService;
    this.settings = settings;
    this.mailLocales = mailLocales;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Registers a local user pending verification and queues the verification email. When the address
   * belongs to an account still pending verification, this registration replaces the unverified
   * one; when the address or the username belongs to any other account, nothing is created and the
   * entered address receives a neutral notice instead. The caller cannot tell the outcomes apart.
   */
  @Transactional
  public void register(String username, String email, String password, String displayName) {
    // Before anything else, so the answer depends on the input alone (anti-enumeration).
    InvalidTextException.requireNoControlCharacters("displayName", displayName);
    // Encoded up front on every branch: the BCrypt cost dominates the request, so paying it only
    // when an account is created would let response times tell the branches apart (issue #903).
    String passwordHash = passwordEncoder.encode(password);
    Optional<User> byEmail = userService.findInternalByEmail(email);
    Optional<User> byUsername = userService.findInternalByUsername(username);

    if (byEmail.isEmpty() && byUsername.isEmpty()) {
      User user =
          userService.createPendingRegistration(
              username, email, passwordHash, displayName, defaultRole());
      queueVerificationEmail(user);
      return;
    }
    if (byEmail.isPresent() && takesOverPending(byEmail.get(), byUsername)) {
      User user =
          userService.replacePendingRegistration(
              byEmail.get().getId(), username, passwordHash, displayName);
      queueVerificationEmail(user);
      return;
    }
    queueAttemptNotice(email, byEmail.map(User::getId).orElse(null));
  }

  /**
   * Whether a registration for the address of {@code existing} replaces it: only while that account
   * is still pending verification, and only if the requested username is free or already its own.
   */
  private static boolean takesOverPending(User existing, Optional<User> byUsername) {
    return existing.isEmailVerificationPending()
        && byUsername.map(other -> other.getId().equals(existing.getId())).orElse(true);
  }

  /**
   * The configured global role for self-registered accounts. Defensively falls back to {@link
   * UserRole#MEMBER} for any unknown value and never lets self-registration mint an {@code ADMIN}.
   */
  private UserRole defaultRole() {
    String configured =
        settings.getString(ApplicationSettingKey.AUTH_SELF_REGISTRATION_DEFAULT_ROLE);
    UserRole role;
    try {
      role = UserRole.valueOf(configured);
    } catch (IllegalArgumentException | NullPointerException e) {
      role = UserRole.MEMBER;
    }
    return role == UserRole.ADMIN ? UserRole.MEMBER : role;
  }

  /**
   * Verifies an address and activates its account (issue #900). The token must be usable, the
   * account must still be pending verification, and {@code password} must be the one chosen at
   * registration; only then is the single-use token spent. A mistyped password leaves the link
   * usable.
   *
   * @throws InvalidVerificationTokenException for an unknown, expired or used token, or an account
   *     that is no longer pending verification (e.g. an administrator disabled it meanwhile)
   * @throws VerificationPasswordMismatchException when the password does not match
   */
  @Transactional
  public void verify(String rawToken, String password) {
    User user = verificationTokens.peek(rawToken);
    if (!user.isEmailVerificationPending()) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
    if (password == null
        || user.getPasswordHash() == null
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new VerificationPasswordMismatchException();
    }
    UUID userId = user.getId();
    verificationTokens.consume(rawToken);
    // Conditional on the pending flag in the database, so an administrator's decision taken after
    // the checks above still wins; the throw rolls the token consumption back with it.
    if (!userService.activatePendingVerification(userId)) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
  }

  /**
   * A brand-new account has no language preference yet, so this resolves to the application default
   * (issue #853) — through the same resolver as every other mail, so a preference stored in the
   * same transaction would be honoured too.
   */
  private void queueVerificationEmail(User user) {
    var issued = verificationTokens.issue(user);
    String locale = mailLocales.forUser(user.getId());
    mailService.sendMailFromTemplateAfterCommit(
        MailTemplateKey.REGISTRATION_VERIFICATION,
        user.getEmail(),
        Map.of(
            "siteName", siteName(),
            "recipientName", user.getDisplayName(),
            "actionUrl", verifyUrl(issued.rawToken()),
            "expiresAtHuman", RelativeTimePhrase.until(issued.expiresAt(), locale)),
        locale);
  }

  /**
   * The neutral notice for a registration that collided with an existing account. In the account
   * owner's language when the address belongs to one, the application default otherwise.
   */
  private void queueAttemptNotice(String email, UUID existingUserId) {
    String locale = mailLocales.forUser(existingUserId);
    mailService.sendMailFromTemplateAfterCommit(
        MailTemplateKey.REGISTRATION_ATTEMPT,
        email,
        Map.of("siteName", siteName(), "actionUrl", baseUrl() + "/login"),
        locale);
  }

  private String verifyUrl(String rawToken) {
    return baseUrl() + "/verify-email?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
  }

  private String siteName() {
    return settings.getString(ApplicationSettingKey.GENERAL_APPLICATION_NAME);
  }

  private String baseUrl() {
    String base = settings.getString(ApplicationSettingKey.GENERAL_BASE_URL);
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** The password presented at verification is not the one chosen at registration (issue #900). */
  public static class VerificationPasswordMismatchException extends RuntimeException {
    public VerificationPasswordMismatchException() {
      super("The password does not match the one chosen at registration");
    }
  }
}
