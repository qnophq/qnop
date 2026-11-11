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
package io.qnop.service;

import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential verification and password management for local (INTERNAL) users (issue #17). Keeps the
 * repository and entity access in the service layer so the web layer never touches them directly
 * (ADR-0004); the controller orchestrates token issuance on top of the results returned here.
 */
@Service
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenRevocationService tokenRevocationService;
  private final RefreshTokenService refreshTokenService;

  /**
   * A hash of a random, never-disclosed secret, compared against on every login that has no usable
   * stored hash (issue #903), so every path pays exactly one password comparison.
   */
  private final String dummyPasswordHash;

  public AuthService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      TokenRevocationService tokenRevocationService,
      RefreshTokenService refreshTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenRevocationService = tokenRevocationService;
    this.refreshTokenService = refreshTokenService;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * Verifies local credentials. Accepts the username or the (case-insensitive) email. Returns the
   * user id on success, or empty on any failure (unknown user, disabled, wrong password) — the
   * caller must not distinguish the reasons.
   *
   * <p>Exactly one password comparison runs on every path (issue #903): an unknown, disabled or
   * password-less account is compared against a dummy hash, so the response time does not reveal
   * whether an account exists.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> authenticate(String usernameOrEmail, String password) {
    return authenticate(usernameOrEmail, password, account -> true);
  }

  /**
   * {@link #authenticate(String, String)} with a gate consulted between the account lookup and the
   * password comparison (issue #899): it receives the id of the local account the identifier
   * resolves to (empty when none), so a per-account login throttle keys on exactly the account the
   * credential check means, whichever of username or e-mail spelling was used, without a second
   * lookup. The gate may throw to refuse the attempt outright, or return {@code false} to fail it
   * silently; the password comparison runs either way, so a silent refusal takes as long as a real
   * one.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> authenticate(
      String usernameOrEmail, String password, Predicate<Optional<UUID>> gate) {
    User user = findLocalAccount(usernameOrEmail).orElse(null);
    boolean admitted = gate.test(Optional.ofNullable(user).map(User::getId));
    boolean usable = user != null && user.isEnabled() && user.getPasswordHash() != null;
    boolean matches =
        passwordEncoder.matches(password, usable ? user.getPasswordHash() : dummyPasswordHash);
    return admitted && usable && matches ? Optional.of(user.getId()) : Optional.empty();
  }

  private Optional<User> findLocalAccount(String usernameOrEmail) {
    if (usernameOrEmail == null) {
      return Optional.empty();
    }
    return userRepository
        .findByUsernameAndSource(usernameOrEmail, UserSource.INTERNAL)
        .or(
            () ->
                userRepository.findByEmailIgnoreCaseAndSource(
                    usernameOrEmail, UserSource.INTERNAL));
  }

  /**
   * Re-verifies the current password, stores the new hash, clears any forced-change requirement
   * ({@code password_change_required}), and invalidates every existing session (access tokens via
   * {@code passwordInvalidatedBefore}; refresh families revoked).
   */
  @Transactional
  public ChangePasswordOutcome changePassword(
      UUID userId, String currentPassword, String newPassword) {
    PasswordPolicy.require("newPassword", newPassword);
    User user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      return ChangePasswordOutcome.USER_NOT_FOUND;
    }
    if (user.getSource() != UserSource.INTERNAL || user.getPasswordHash() == null) {
      return ChangePasswordOutcome.NOT_LOCAL;
    }
    if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      return ChangePasswordOutcome.WRONG_PASSWORD;
    }
    // Atomic, version-bumping updates (issue #61): the loaded `user` is read for verification only
    // and never dirty-saved, so the hash change and the revocation below can't clobber each other
    // or a concurrent edit.
    userRepository.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
    tokenRevocationService.revokeAllForUser(userId);
    refreshTokenService.revokeAllForUser(userId);
    return ChangePasswordOutcome.SUCCESS;
  }

  /** Result of a {@link #changePassword} attempt. */
  public enum ChangePasswordOutcome {
    SUCCESS,
    WRONG_PASSWORD,
    NOT_LOCAL,
    USER_NOT_FOUND
  }
}
