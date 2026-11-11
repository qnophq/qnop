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
import io.qnop.service.UserService.ActivationOutcome;
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
 *   <li>The credentials a registration asks for are bound to <em>its</em> verification token, not
 *       to the account row. Registering an address that is still pending issues one more token and
 *       leaves the others valid; verifying a token with its password activates the account with
 *       exactly that token's credentials and spends all other tokens. Nobody can block or
 *       invalidate somebody else's registration, and only the mailbox owner, who receives the
 *       links, can complete any of them.
 *   <li>Each link allows a capped number of password attempts, reserved atomically before the
 *       comparison.
 *   <li>A registration that collides with an existing account sends a neutral notice to the entered
 *       address instead of nothing; the response stays uniform either way.
 *   <li>Every branch encodes the password once and every mail is sent after commit off the request
 *       thread, so the response time does not tell the branches apart.
 * </ul>
 */
@Service
public class RegistrationService {

  /**
   * At most one new verification link (and mail) per pending address in this window (issue #900).
   * With the cap of {@value EmailVerificationTokenService#MAX_OUTSTANDING_TOKENS} outstanding
   * links, displacing an existing link takes at least that many windows; per-address register rate
   * limits come on top.
   */
  static final Duration ISSUE_THROTTLE = Duration.ofMinutes(5);

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
   * Verifies an address and activates its account (issue #900) with the credentials of the
   * registration this token belongs to. The token must be usable, the account must still be pending
   * verification, and {@code password} must be that registration's password. One attempt is
   * reserved atomically before the comparison, so no more than {@value
   * EmailVerificationTokenService#MAX_FAILED_ATTEMPTS} passwords are ever compared against one
   * link, concurrency included; the attempt that uses up the allowance spends the link. On success
   * all other links of the address are spent.
   *
   * @throws InvalidVerificationTokenException for an unknown, expired, used or spent token, or an
   *     account that is no longer pending verification (e.g. an administrator disabled it)
   * @throws VerificationPasswordMismatchException when the password does not match
   * @throws VerificationUsernameTakenException when the registration's username was taken meanwhile
   *     (the link is spent)
   */
  @Transactional(
      noRollbackFor = {
        VerificationPasswordMismatchException.class,
        VerificationUsernameTakenException.class
      })
  public void verify(String rawToken, String password) {
    PendingVerification pending = verificationTokens.peek(rawToken);
    if (!pending.accountPending()) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
    // Account row first, then the token: every verify and every registration of this address
    // takes the locks in this order.
    userService.lockForUpdate(pending.userId());
    if (!verificationTokens.reserveAttempt(pending.tokenId())) {
      throw new InvalidVerificationTokenException("Verification token is no longer valid");
    }
    if (password == null
        || pending.passwordHash() == null
        || !passwordEncoder.matches(password, pending.passwordHash())) {
      // Committed despite the exception (noRollbackFor): the reserved attempt stays counted.
      throw new VerificationPasswordMismatchException(
          verificationTokens.spendIfExhausted(pending.tokenId()));
    }
    // Conditional on the pending flag in the database, so an administrator's decision or another
    // link verified concurrently still wins; that throw rolls everything back.
    ActivationOutcome outcome =
        userService.activateVerifiedRegistration(
            pending.userId(), pending.username(), pending.displayName(), pending.passwordHash());
    switch (outcome) {
      case USERNAME_TAKEN -> {
        // This link can never succeed: spend it (committed, noRollbackFor) instead of letting
        // every retry cost another password comparison.
        verificationTokens.consume(pending.tokenId());
        throw new VerificationUsernameTakenException();
      }
      case NOT_PENDING ->
          throw new InvalidVerificationTokenException("Verification token is no longer valid");
      case ACTIVATED -> {
        verificationTokens.consume(pending.tokenId());
        verificationTokens.consumeAllForUser(pending.userId());
      }
    }
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
      super("The username of this registration is no longer available; please register again");
    }
  }

  /** The password presented at verification is not the one chosen at registration (issue #900). */
  public static class VerificationPasswordMismatchException extends RuntimeException {
    private final boolean linkSpent;

    public VerificationPasswordMismatchException(boolean linkSpent) {
      super(
          linkSpent
              ? "Too many wrong passwords; this verification link can no longer be used"
              : "The password does not match the one chosen at registration");
      this.linkSpent = linkSpent;
    }

    /** Whether this attempt used up the allowance and spent the verification link. */
    public boolean linkSpent() {
      return linkSpent;
    }
  }
}
