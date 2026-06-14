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
package io.qnop.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.entity.OidcIdentity;
import io.qnop.entity.OidcProvider;
import io.qnop.entity.OidcProviderType;
import io.qnop.entity.User;
import io.qnop.entity.UserSource;
import io.qnop.repository.OidcIdentityRepository;
import io.qnop.repository.OidcProviderRepository;
import io.qnop.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the identity schema (issue #11) against a real PostgreSQL (ADR-0020): UUIDv7 generation,
 * the Postgres-only CHECK and partial-unique constraints that JPA cannot express, encryption of the
 * OIDC client secret at rest, and the {@code ON DELETE CASCADE} foreign keys. Each test runs in a
 * rolled-back transaction for isolation. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional
class IdentitySchemaIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @Autowired UserRepository users;
  @Autowired OidcProviderRepository providers;
  @Autowired OidcIdentityRepository identities;
  @Autowired JdbcTemplate jdbc;

  @Test
  void persistsInternalUserWithGeneratedUuidV7() {
    User saved = users.saveAndFlush(User.internal("Alice", "alice@example.com", "alice", "hash"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).isEqualTo(7);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void rejectsInternalUserWithoutCredentials() {
    User invalid = User.external("Bob", "bob@example.com");
    invalid.setSource(UserSource.INTERNAL); // INTERNAL but missing username + password_hash

    assertThatThrownBy(() -> users.saveAndFlush(invalid))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void enforcesCaseInsensitiveEmailUniquenessForInternalUsers() {
    users.saveAndFlush(User.internal("A", "dup@example.com", "user-a", "hash"));

    assertThatThrownBy(
            () -> users.saveAndFlush(User.internal("B", "DUP@EXAMPLE.COM", "user-b", "hash")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void allowsDuplicateEmailForExternalUsers() {
    users.saveAndFlush(User.external("A", "shared@example.com"));

    User second = users.saveAndFlush(User.external("B", "shared@example.com"));

    assertThat(second.getId()).isNotNull();
  }

  @Test
  void encryptsClientSecretAtRest() {
    OidcProvider provider = new OidcProvider("github", OidcProviderType.GITHUB, "client-123");
    provider.setClientSecret("super-secret-value");
    OidcProvider saved = providers.saveAndFlush(provider);

    String rawColumn =
        jdbc.queryForObject(
            "SELECT client_secret_encrypted FROM oidc_provider WHERE id = ?",
            String.class,
            saved.getId());

    assertThat(rawColumn).isNotNull().isNotEqualTo("super-secret-value");
    assertThat(saved.getClientSecret()).isEqualTo("super-secret-value"); // transparent on read
  }

  @Test
  void enforcesProviderSubjectUniqueness() {
    OidcProvider provider =
        providers.saveAndFlush(new OidcProvider("google", OidcProviderType.GOOGLE, "cid"));
    User userA = users.saveAndFlush(User.external("Ext A", "exta@example.com"));
    User userB = users.saveAndFlush(User.external("Ext B", "extb@example.com"));
    identities.saveAndFlush(new OidcIdentity(provider.getId(), "subject-1", userA.getId()));

    assertThatThrownBy(
            () ->
                identities.saveAndFlush(
                    new OidcIdentity(provider.getId(), "subject-1", userB.getId())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void cascadesIdentityDeletionWhenUserRemoved() {
    OidcProvider provider =
        providers.saveAndFlush(new OidcProvider("facebook", OidcProviderType.FACEBOOK, "cid"));
    User user = users.saveAndFlush(User.external("Z", "z@example.com"));
    OidcIdentity identity =
        identities.saveAndFlush(new OidcIdentity(provider.getId(), "subject-z", user.getId()));
    UUID identityId = identity.getId();

    users.deleteById(user.getId());
    users.flush();

    assertThat(identities.findById(identityId)).isEmpty();
  }
}
