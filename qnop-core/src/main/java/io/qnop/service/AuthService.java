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

  public AuthService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      TokenRevocationService tokenRevocationService,
      RefreshTokenService refreshTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenRevocationService = tokenRevocationService;
    this.refreshTokenService = refreshTokenService;
  }

  /**
   * Verifies local credentials. Accepts the username or the (case-insensitive) email. Returns the
   * user id on success, or empty on any failure (unknown user, disabled, wrong password) — the
   * caller must not distinguish the reasons.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> authenticate(String usernameOrEmail, String password) {
    User user =
        userRepository
            .findByUsernameAndSource(usernameOrEmail, UserSource.INTERNAL)
            .or(
                () ->
                    userRepository.findByEmailIgnoreCaseAndSource(
                        usernameOrEmail, UserSource.INTERNAL))
            .orElse(null);
    if (user == null
        || !user.isEnabled()
        || user.getPasswordHash() == null
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      return Optional.empty();
    }
    return Optional.of(user.getId());
  }

  /**
   * Re-verifies the current password, stores the new hash, clears any forced-change requirement
   * ({@code password_change_required}), and invalidates every existing session (access tokens via
   * {@code passwordInvalidatedBefore}; refresh families revoked).
   */
  @Transactional
  public ChangePasswordOutcome changePassword(
      UUID userId, String currentPassword, String newPassword) {
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
