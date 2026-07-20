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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.PasswordResetToken;
import io.qnop.entity.User;
import io.qnop.repository.PasswordResetTokenRepository;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.auth.PasswordResetTokenService.InvalidPasswordResetTokenException;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordResetTokenServiceTest {

  @Mock private PasswordResetTokenRepository tokens;
  @Mock private ApplicationSettingsService settings;
  @Mock private SchedulerService scheduler;
  private PasswordResetTokenService service;
  private final User user = User.internal("Jane", "jane@example.com", "jane", "hash");

  @BeforeEach
  void setUp() {
    service = new PasswordResetTokenService(tokens, settings, scheduler);
  }

  @Test
  @DisplayName("issue stores a token with the configured TTL and returns the raw value")
  void issueStoresToken() {
    when(settings.getInteger(ApplicationSettingKey.AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES))
        .thenReturn(30);
    when(tokens.findUnconsumedTokensForUser(any())).thenReturn(List.of());

    PasswordResetTokenService.IssuedResetToken issued = service.issue(user);

    assertThat(issued.rawToken()).isNotBlank();
    assertThat(issued.expiresAt()).isAfter(Instant.now().plusSeconds(29 * 60));
    verify(tokens).save(any(PasswordResetToken.class));
  }

  @Test
  @DisplayName("issue supersedes any earlier unconsumed token for the user")
  void issueSupersedesPrior() {
    when(settings.getInteger(ApplicationSettingKey.AUTH_PASSWORD_RESET_TOKEN_TTL_MINUTES))
        .thenReturn(30);
    PasswordResetToken prior =
        new PasswordResetToken(user, "oldhash", Instant.now().plusSeconds(60));
    when(tokens.findUnconsumedTokensForUser(any())).thenReturn(List.of(prior));

    service.issue(user);

    assertThat(prior.getConsumedAt()).isNotNull();
  }

  @Test
  @DisplayName("consume returns the owner and marks the token used")
  void consumeValid() {
    PasswordResetToken token = new PasswordResetToken(user, "h", Instant.now().plusSeconds(60));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));
    when(tokens.markConsumed(any(), any())).thenReturn(1);

    User result = service.consume("raw-token");

    assertThat(result).isSameAs(user);
    verify(tokens).markConsumed(eq(token.getId()), any(Instant.class));
  }

  @Test
  @DisplayName("consume rejects a token consumed concurrently (atomic guard, issue #61)")
  void consumeLosesRace() {
    PasswordResetToken token = new PasswordResetToken(user, "h", Instant.now().plusSeconds(60));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));
    when(tokens.markConsumed(any(), any())).thenReturn(0); // another request already won

    assertThatThrownBy(() -> service.consume("raw-token"))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }

  @Test
  @DisplayName("consume rejects an expired token")
  void consumeExpired() {
    PasswordResetToken token = new PasswordResetToken(user, "h", Instant.now().minusSeconds(60));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service.consume("raw-token"))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }

  @Test
  @DisplayName("consume rejects an already-used token")
  void consumeAlreadyUsed() {
    PasswordResetToken token = new PasswordResetToken(user, "h", Instant.now().plusSeconds(60));
    token.setConsumedAt(Instant.now());
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));

    assertThatThrownBy(() -> service.consume("raw-token"))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }

  @Test
  @DisplayName("consume rejects an unknown token")
  void consumeUnknown() {
    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.consume("raw-token"))
        .isInstanceOf(InvalidPasswordResetTokenException.class);
  }
}
