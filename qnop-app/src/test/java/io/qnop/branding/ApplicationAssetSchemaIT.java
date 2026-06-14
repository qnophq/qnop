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
package io.qnop.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.ApplicationAsset;
import io.qnop.entity.BrandingSlot;
import io.qnop.entity.User;
import io.qnop.repository.ApplicationAssetRepository;
import io.qnop.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the application-asset / branding schema (issue #15) against a real PostgreSQL
 * (ADR-0020): UUIDv7 generation, the {@code bytea} round-trip, the snake_case slot mapping, the
 * Postgres-only CHECK and {@code (slot)} unique constraints that JPA cannot express, and the
 * no-cascade {@code uploaded_by} foreign key. Each test runs in a rolled-back transaction for
 * isolation. Extends {@link AbstractIntegrationTest}, which boots the full context against
 * Testcontainers. Requires Docker.
 */
@Transactional
class ApplicationAssetSchemaIT extends AbstractIntegrationTest {

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

  @Autowired ApplicationAssetRepository assets;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;

  private static ApplicationAsset asset(BrandingSlot slot, UUID uploadedBy) {
    return ApplicationAsset.create(slot, "image/png", PNG, "deadbeef", PNG.length, uploadedBy);
  }

  @Test
  void persistsAssetWithGeneratedUuidV7AndTimestamp() {
    ApplicationAsset saved = assets.saveAndFlush(asset(BrandingSlot.LOGO_LIGHT, null));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getId().version()).isEqualTo(7);
    assertThat(saved.getUploadedAt()).isNotNull();
  }

  @Test
  void roundTripsBinaryContent() {
    UUID id = assets.saveAndFlush(asset(BrandingSlot.LOGOMARK, null)).getId();
    assets.flush();

    ApplicationAsset reloaded = assets.findById(id).orElseThrow();

    assertThat(reloaded.getContent()).containsExactly(PNG);
    assertThat(reloaded.getSlot()).isEqualTo(BrandingSlot.LOGOMARK);
    assertThat(reloaded.getSizeBytes()).isEqualTo(PNG.length);
    assertThat(reloaded.getSha256()).isEqualTo("deadbeef");
  }

  @Test
  void storesSlotAsSnakeCaseDbValue() {
    UUID id = assets.saveAndFlush(asset(BrandingSlot.LOGO_DARK, null)).getId();

    String rawSlot =
        jdbc.queryForObject("SELECT slot FROM application_asset WHERE id = ?", String.class, id);

    assertThat(rawSlot).isEqualTo("logo_dark");
  }

  @Test
  void findBySlotReturnsTheCurrentAsset() {
    assets.saveAndFlush(asset(BrandingSlot.LOGO_LIGHT, null));

    assertThat(assets.findBySlot(BrandingSlot.LOGO_LIGHT)).isPresent();
    assertThat(assets.findBySlot(BrandingSlot.LOGO_DARK)).isEmpty();
  }

  @Test
  void enforcesOneAssetPerSlot() {
    assets.saveAndFlush(asset(BrandingSlot.LOGO_LIGHT, null));

    assertThatThrownBy(() -> assets.saveAndFlush(asset(BrandingSlot.LOGO_LIGHT, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsUnsupportedContentType() {
    ApplicationAsset invalid =
        ApplicationAsset.create(BrandingSlot.LOGO_LIGHT, "image/gif", PNG, "sha", PNG.length, null);

    assertThatThrownBy(() -> assets.saveAndFlush(invalid))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsUnknownSlotValueAtTheDatabaseLevel() {
    // The enum makes an invalid slot unreachable through the entity, so assert the
    // defence-in-depth CHECK directly via raw SQL.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO application_asset"
                        + " (id, slot, content_type, content, sha256, size_bytes, uploaded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, now())",
                    UUID.randomUUID(),
                    "favicon",
                    "image/png",
                    PNG,
                    "sha",
                    PNG.length))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void allowsNullUploadedBy() {
    ApplicationAsset saved = assets.saveAndFlush(asset(BrandingSlot.LOGOMARK, null));

    assertThat(saved.getUploadedBy()).isNull();
  }

  @Test
  void blocksUserDeletionWhileAssetReferencesThem() {
    User uploader = users.saveAndFlush(User.external("Uploader", "uploader@example.com"));
    assets.saveAndFlush(asset(BrandingSlot.LOGO_DARK, uploader.getId()));

    assertThatThrownBy(
            () -> {
              users.deleteById(uploader.getId());
              users.flush(); // force the DELETE so the no-cascade FK rejects it
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
