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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.entity.EmailVerificationToken;
import io.qnop.entity.User;
import io.qnop.repository.EmailVerificationTokenRepository;
import io.qnop.service.auth.EmailVerificationTokenService.InvalidVerificationTokenException;
import io.qnop.service.auth.EmailVerificationTokenService.PendingVerification;
import io.qnop.service.scheduler.SchedulerService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationTokenServiceTest {

  @Mock private EmailVerificationTokenRepository tokens;
  @Mock private SchedulerService scheduler;
  private EmailVerificationTokenService service;
  private final User user = User.internal("Jane", "jane@example.com", "jane", "row-hash");

  @BeforeEach
  void setUp() {
    service = new EmailVerificationTokenService(tokens, scheduler);
  }

  private static EmailVerificationToken live() {
    return new EmailVerificationToken(null, "h", Instant.now().plusSeconds(60));
  }

  @Test
  @DisplayName("issueForRegistration stores a 24h token carrying the registration's credentials")
  void issueStoresCandidate() {
    when(tokens.findUnconsumedIdsForUserOldestFirst(any())).thenReturn(List.of());

    var issued = service.issueForRegistration(user, "jane2", "Jane Two", "cand-hash");

    assertThat(issued.rawToken()).isNotBlank();
    assertThat(issued.expiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
    ArgumentCaptor<EmailVerificationToken> saved =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokens).save(saved.capture());
    assertThat(saved.getValue().getCandidateUsername()).isEqualTo("jane2");
    assertThat(saved.getValue().getCandidateDisplayName()).isEqualTo("Jane Two");
    assertThat(saved.getValue().getCandidatePasswordHash()).isEqualTo("cand-hash");
  }

  @Test
  @DisplayName("other outstanding links stay valid while fewer than the cap are outstanding")
  void issueKeepsOtherLinks() {
    when(tokens.findUnconsumedIdsForUserOldestFirst(any()))
        .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

    service.issueForRegistration(user, "jane", "Jane", "h");

    verify(tokens, never()).consumeAllById(any(), any());
  }

  @Test
  @DisplayName("at the cap the oldest outstanding links are spent")
  void issueEvictsTheOldest() {
    List<UUID> outstanding = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      outstanding.add(UUID.randomUUID());
    }
    when(tokens.findUnconsumedIdsForUserOldestFirst(any())).thenReturn(outstanding);

    service.issueForRegistration(user, "jane", "Jane", "h");

    // 4 outstanding + 1 new, cap 3: the two oldest go, in one targeted update.
    verify(tokens).consumeAllById(eq(outstanding.subList(0, 2)), any());
  }

  @Test
  @DisplayName("issuedWithin compares the newest link's age with the window")
  void issuedWithinUsesTheNewestLink() {
    UUID userId = UUID.randomUUID();
    when(tokens.findLatestIssuedAt(userId)).thenReturn(Optional.of(Instant.now().minusSeconds(60)));
    assertThat(service.issuedWithin(userId, java.time.Duration.ofMinutes(5))).isTrue();
    assertThat(service.issuedWithin(userId, java.time.Duration.ofSeconds(30))).isFalse();

    when(tokens.findLatestIssuedAt(userId)).thenReturn(Optional.empty());
    assertThat(service.issuedWithin(userId, java.time.Duration.ofMinutes(5))).isFalse();
  }

  @Test
  @DisplayName("peek returns the token's candidate credentials without consuming it")
  void peekReturnsCandidate() {
    User pending = User.internal("Row", "jane@example.com", "row", "row-hash");
    pending.markPendingEmailVerification();
    EmailVerificationToken token =
        new EmailVerificationToken(
            pending, "h", Instant.now().plusSeconds(60), "jane", "Jane", "cand-hash");
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));

    PendingVerification peeked = service.peek("raw-token");

    assertThat(peeked.accountPending()).isTrue();
    assertThat(peeked.username()).isEqualTo("jane");
    assertThat(peeked.displayName()).isEqualTo("Jane");
    assertThat(peeked.passwordHash()).isEqualTo("cand-hash");
  }

  @Test
  @DisplayName("a token issued before candidates existed falls back to the account row")
  void peekFallsBackForLegacyTokens() {
    EmailVerificationToken token =
        new EmailVerificationToken(user, "h", Instant.now().plusSeconds(60));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(token));

    PendingVerification peeked = service.peek("raw-token");

    assertThat(peeked.username()).isEqualTo("jane");
    assertThat(peeked.passwordHash()).isEqualTo("row-hash");
  }

  @Test
  @DisplayName("peek rejects an expired, used or unknown token")
  void peekRejectsUnusable() {
    EmailVerificationToken expired =
        new EmailVerificationToken(user, "h", Instant.now().minusSeconds(60));
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.peek("raw-token"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    EmailVerificationToken used =
        new EmailVerificationToken(user, "h", Instant.now().plusSeconds(60));
    used.setConsumedAt(Instant.now());
    when(tokens.findByTokenHash(any())).thenReturn(Optional.of(used));
    assertThatThrownBy(() -> service.peek("raw-token"))
        .isInstanceOf(InvalidVerificationTokenException.class);

    when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.peek("raw-token"))
        .isInstanceOf(InvalidVerificationTokenException.class);
  }

  @Test
  @DisplayName("reserveAttempt is the conditional update against the cap")
  void reserveAttemptUsesTheCap() {
    UUID id = UUID.randomUUID();
    when(tokens.reserveAttempt(id, EmailVerificationTokenService.MAX_FAILED_ATTEMPTS))
        .thenReturn(1, 0);

    assertThat(service.reserveAttempt(id)).isTrue();
    assertThat(service.reserveAttempt(id)).isFalse();
  }

  @Test
  @DisplayName("spendIfExhausted reports whether this call spent the link")
  void spendIfExhausted() {
    UUID id = UUID.randomUUID();
    when(tokens.consumeIfAttemptsExhausted(eq(id), eq(5), any())).thenReturn(0, 1);

    assertThat(service.spendIfExhausted(id)).isFalse();
    assertThat(service.spendIfExhausted(id)).isTrue();
  }

  @Test
  @DisplayName("consume rejects a token consumed concurrently (atomic guard, issue #61)")
  void consumeLosesRace() {
    UUID id = UUID.randomUUID();
    when(tokens.markConsumed(eq(id), any())).thenReturn(0);

    assertThatThrownBy(() -> service.consume(id))
        .isInstanceOf(InvalidVerificationTokenException.class);
  }

  @Test
  @DisplayName("consumeAllForUser spends every outstanding link of the account")
  void consumeAllForUser() {
    UUID userId = UUID.randomUUID();

    service.consumeAllForUser(userId);

    verify(tokens).consumeAllForUser(eq(userId), any());
  }
}
