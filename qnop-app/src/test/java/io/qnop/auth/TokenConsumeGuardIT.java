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
package io.qnop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.EmailVerificationToken;
import io.qnop.entity.PasswordResetToken;
import io.qnop.entity.User;
import io.qnop.repository.EmailVerificationTokenRepository;
import io.qnop.repository.PasswordResetTokenRepository;
import io.qnop.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the single-use token guard (issue #61, ADR-0030): the conditional {@code UPDATE … WHERE
 * consumed_at IS NULL} consumes a token at most once. The first call wins (1 row); a second
 * concurrent call — the case that the old check-then-act {@code consume()} would have let through —
 * affects 0 rows. Runs against a real PostgreSQL (Testcontainers); each test rolls back. Requires
 * Docker.
 */
@Transactional
class TokenConsumeGuardIT extends AbstractIntegrationTest {

  @Autowired UserRepository users;
  @Autowired EmailVerificationTokenRepository emailTokens;
  @Autowired PasswordResetTokenRepository resetTokens;

  private User newUser() {
    return users.saveAndFlush(
        User.internal("T", "t-" + UUID.randomUUID() + "@e.com", "u-" + UUID.randomUUID(), "h"));
  }

  @Test
  void emailVerificationTokenIsConsumedAtMostOnce() {
    User user = newUser();
    UUID id =
        emailTokens
            .saveAndFlush(
                new EmailVerificationToken(user, "ev-hash", Instant.now().plusSeconds(3600)))
            .getId();

    assertThat(emailTokens.markConsumed(id, Instant.now())).isEqualTo(1);
    assertThat(emailTokens.markConsumed(id, Instant.now())).isZero();
  }

  @Test
  void passwordResetTokenIsConsumedAtMostOnce() {
    User user = newUser();
    UUID id =
        resetTokens
            .saveAndFlush(new PasswordResetToken(user, "pr-hash", Instant.now().plusSeconds(3600)))
            .getId();

    assertThat(resetTokens.markConsumed(id, Instant.now())).isEqualTo(1);
    assertThat(resetTokens.markConsumed(id, Instant.now())).isZero();
  }
}
