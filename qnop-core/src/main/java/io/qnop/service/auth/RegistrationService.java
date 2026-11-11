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
import io.qnop.service.auth.EmailVerificationTokenService.PendingVerification;
import io.qnop.service.mail.MailLocaleResolver;
import io.qnop.service.mail.MailService;
import io.qnop.service.mail.MailTemplateKey;
import io.qnop.service.mail.RelativeTimePhrase;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
 *   <li><em>Mailbox control is account control</em> for an account nobody has verified yet — the
 *       same trust a password-reset link carries. Each registration's credentials ride on its own
 *       verification link; whoever uses a link either confirms that registration (its password) or
 *       sets new credentials. So the owner of an address can always claim the account with any link
 *       that reaches their mailbox, whoever triggered it, and a registration by somebody else can
 *       never complete or block anything.
 *   <li>A registration that collides with an existing account sends a neutral notice to the entered
 *       address instead of nothing; the response stays uniform either way.
 *   <li>Every branch encodes the password once and every mail is sent after commit off the request
 *       thread, so the response time does not tell the branches apart.
 * </ul>
 */
@Service
public class RegistrationService {

  /**
   * At most one new verification link (and mail) per pending address in this window (issue #900) —
   * purely mail-volume protection: inside the window the address has just received a link, and any
   * link lets its owner claim the account.
   */
  static final Duration ISSUE_THROTTLE = Duration.ofMinutes(5);

  // The self-registration policy, as in the OpenAPI RegisterRequest (issue #900).
  static final int MIN_PASSWORD_LENGTH = 8;
  static final int MAX_PASSWORD_LENGTH = 200;
  static final int MIN_USERNAME_LENGTH = 3;
  static final int MAX_USERNAME_LENGTH = 64;

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
   * belongs to an account still pending verification, this registration gets a verification link of
   * its own next to the existing ones; when the address or the username belongs to any other
   * account, nothing is created and the entered address receives a neutral notice instead. The
   * caller cannot tell the outcomes apart.
   */
  @Transactional
  public void register(String username, String email, String password, String displayName) {
    // Before anything else, so the answer depends on the input alone (anti-enumeration).
    InvalidTextException.requireNoControlCharacters("displayName", displayName);
    // Encoded up front on every branch: the BCrypt cost dominates the request, so paying it only
    // when an account is created would let response times tell the branches apart (issue #903).
    String passwordHash = passwordEncoder.encode(password);
    String name = displayName == null || displayName.isBlank() ? username : displayName;
    Optional<User> byEmail = userService.findInternalByEmail(email);
    Optional<User> byUsername = userService.findInternalByUsername(username);

    if (byEmail.isEmpty() && byUsername.isEmpty()) {
      User user =
          userService.createPendingRegistration(username, email, displayName, defaultRole());
      queueVerificationEmail(user, username, name, passwordHash);
      return;
    }
    if (byEmail.isPresent() && joinsPending(byEmail.get(), byUsername)) {
      User pending = byEmail.get();
      // Account row first, then its tokens — the same order as verify — so concurrent
      // registrations of one address serialise and the link cap stays exact.
      userService.lockForUpdate(pending.getId());
      if (verificationTokens.issuedWithin(pending.getId(), ISSUE_THROTTLE)) {
        // Per-address throttle: no new link, no mail (the response stays the same).
        return;
      }
      queueVerificationEmail(pending, username, name, passwordHash);
      return;
    }
    queueAttemptNotice(email, byEmail.map(User::getId).orElse(null));
  }

  /**
   * Whether a registration for the address of {@code existing} gets a link of its own: only while
   * that account is still pending verification, and only if the requested username is free or is
   * the pending account's own.
   */
  private static boolean joinsPending(User existing, Optional<User> byUsername) {
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
   * Verifies an address and activates its account (issue #900). The verification link proves
   * control of the mailbox, and for an account nobody has verified yet mailbox control is account
   * control — the same trust a password-reset link carries. So whoever uses the link decides the
   * credentials:
   *
   * <ul>
   *   <li>{@code setNew = false} confirms the registration this link belongs to: {@code password}
   *       must be its password, and the account gets exactly its username, display name and
   *       password. A mismatch changes nothing and leaves the link usable.
   *   <li>{@code setNew = true} sets new credentials instead: {@code password} becomes the
   *       password, {@code username} (default: the registration's) the username and the display
   *       name. This is how the owner of an address claims an account somebody else registered with
   *       it — no registration by anybody else can prevent that.
   * </ul>
   *
   * <p>Either way every other link of the address is spent. There is no attempt cap: a link holder
   * gains nothing by guessing the registration's password, since they may simply set a new one;
   * request volume is bounded by the per-IP rate limit of the endpoint.
   *
   * @throws InvalidVerificationTokenException for an unknown, expired or used token, or an account
   *     that is no longer pending verification (e.g. an administrator decided about it)
   * @throws VerificationPasswordMismatchException when confirming with another password
   * @throws VerificationUsernameTakenException when the username belongs to another account (the
   *     link stays usable; set new credentials with another username)
   * @throws VerificationInputException when new credentials violate the account policy
   */
  @Transactional
  public void verify(String rawToken, String password, boolean setNew, String username) {
    PendingVerification pending = verificationTokens.peek(rawToken);
    if (!pending.accountPending()) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
    // Account row first, then the token: every verify and every registration of this address
    // takes the locks in this order.
    userService.lockForUpdate(pending.userId());
    String chosenUsername;
    String displayName;
    String passwordHash;
    if (setNew) {
      chosenUsername = username == null || username.isBlank() ? pending.username() : username;
      requireValidNewCredentials(chosenUsername, password);
      // The display name of a claimed account is its username: nothing the registrations wrote
      // survives into it. The owner can change it in the profile.
      displayName = chosenUsername;
      passwordHash = passwordEncoder.encode(password);
    } else {
      if (password == null
          || pending.passwordHash() == null
          || !passwordEncoder.matches(password, pending.passwordHash())) {
        throw new VerificationPasswordMismatchException();
      }
      chosenUsername = pending.username();
      displayName = pending.displayName();
      passwordHash = pending.passwordHash();
    }
    // Conditional on the pending flag in the database, so an administrator's decision or another
    // link verified concurrently still wins.
    switch (userService.activateVerifiedRegistration(
        pending.userId(), chosenUsername, displayName, passwordHash)) {
      case USERNAME_TAKEN -> throw new VerificationUsernameTakenException();
      case NOT_PENDING ->
          throw new InvalidVerificationTokenException("Verification token is no longer valid");
      case ACTIVATED -> {
        verificationTokens.consume(pending.tokenId());
        verificationTokens.consumeAllForUser(pending.userId());
      }
    }
  }

  /**
   * The username the registration behind a still-usable link asked for (issue #900), so the verify
   * page can offer it when its holder sets new credentials. Holding the link is the authority here,
   * exactly as for {@link #verify}.
   */
  @Transactional(readOnly = true)
  public String registrationUsername(String rawToken) {
    PendingVerification pending = verificationTokens.peek(rawToken);
    if (!pending.accountPending()) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
    return pending.username();
  }

  /** The self-registration policy for credentials chosen at verification (same as register). */
  private static void requireValidNewCredentials(String username, String password) {
    if (password == null
        || password.length() < MIN_PASSWORD_LENGTH
        || password.length() > MAX_PASSWORD_LENGTH) {
      throw new VerificationInputException(
          "The password must be "
              + MIN_PASSWORD_LENGTH
              + " to "
              + MAX_PASSWORD_LENGTH
              + " characters long.");
    }
    if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
      throw new VerificationInputException(
          "The username must be "
              + MIN_USERNAME_LENGTH
              + " to "
              + MAX_USERNAME_LENGTH
              + " characters long.");
    }
    InvalidTextException.requireNoControlCharacters("username", username);
  }

  /**
   * Issues this registration's own link and queues the mail. The greeting never uses a name from
   * the request — whoever registers an address need not be its owner — but the address's own local
   * part. The language is the account's (a brand-new account has none yet, so the application
   * default applies, issue #853).
   */
  private void queueVerificationEmail(
      User user, String username, String displayName, String passwordHash) {
    var issued = verificationTokens.issueForRegistration(user, username, displayName, passwordHash);
    String locale = mailLocales.forUser(user.getId());
    mailService.sendMailFromTemplateAfterCommit(
        MailTemplateKey.REGISTRATION_VERIFICATION,
        user.getEmail(),
        Map.of(
            "siteName", siteName(),
            "recipientName", localPart(user.getEmail()),
            "actionUrl", verifyUrl(issued.rawToken()),
            "expiresAtHuman", RelativeTimePhrase.until(issued.expiresAt(), locale)),
        locale);
  }

  /**
   * The neutral notice for a registration that collided with an existing account: no text from the
   * request, only the site name and the sign-in link. In the account owner's language when the
   * address belongs to one, the application default otherwise.
   */
  private void queueAttemptNotice(String email, java.util.UUID existingUserId) {
    String locale = mailLocales.forUser(existingUserId);
    mailService.sendMailFromTemplateAfterCommit(
        MailTemplateKey.REGISTRATION_ATTEMPT,
        email,
        Map.of("siteName", siteName(), "actionUrl", baseUrl() + "/login"),
        locale);
  }

  /** The part of an address before the {@code @}: the owner's own text, never the requester's. */
  private static String localPart(String email) {
    int at = email.indexOf('@');
    return at > 0 ? email.substring(0, at) : email;
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

  /**
   * The verified registration's username was taken by another account in the meantime (issue #900);
   * the link is spent and the registrant registers again with another username.
   */
  public static class VerificationUsernameTakenException extends RuntimeException {
    public VerificationUsernameTakenException() {
      super("This username is already taken; choose another one");
    }
  }

  /** The password presented at verification is not the one chosen at registration (issue #900). */
  public static class VerificationPasswordMismatchException extends RuntimeException {
    public VerificationPasswordMismatchException() {
      super("The password does not match the one chosen at registration");
    }
  }

  /** New credentials chosen at verification violate the account policy (issue #900). */
  public static class VerificationInputException extends RuntimeException {
    public VerificationInputException(String message) {
      super(message);
    }
  }
}
