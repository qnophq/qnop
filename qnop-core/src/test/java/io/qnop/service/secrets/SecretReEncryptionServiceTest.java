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
package io.qnop.service.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.qnop.entity.AuditEvent;
import io.qnop.repository.ApplicationSettingRepository;
import io.qnop.repository.AuditEventRepository;
import io.qnop.repository.OidcProviderRepository;
import io.qnop.security.SecretCipher;
import io.qnop.security.SecretCipher.KeyMaterial;
import io.qnop.security.SecretWriteFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/** The start-up pass's guard rails (issue #927): legacy mode, never fatal. */
class SecretReEncryptionServiceTest {

  private static final KeyMaterial KEY =
      new KeyMaterial(
          "unit-test-encryption-key-0123456789-abc", "0123456789abcdef0123456789abcdef");

  private final ApplicationSettingRepository settings = mock(ApplicationSettingRepository.class);
  private final OidcProviderRepository oidcProviders = mock(OidcProviderRepository.class);
  private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private SecretReEncryptionService service(SecretWriteFormat format) {
    return new SecretReEncryptionService(
        new SecretCipher(KEY, null, format), settings, oidcProviders, auditEvents, transactions);
  }

  @Test
  void legacyWriteFormatSkipsThePassEntirely() {
    service(SecretWriteFormat.LEGACY).reEncryptOnStartup();

    verifyNoInteractions(settings, oidcProviders, auditEvents);
  }

  @Test
  void aFailingPassIsLoggedAndAuditedButNeverStopsStartUp() {
    when(settings.findAll())
        .thenThrow(new DataAccessResourceFailureException("connection refused: db-host:5432"));

    assertThatCode(() -> service(SecretWriteFormat.V2).reEncryptOnStartup())
        .doesNotThrowAnyException();

    ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEvents).save(saved.capture());
    assertThat(saved.getValue().getEventType()).isEqualTo("secrets.reencryption_failed");
    assertThat(saved.getValue().getDetail())
        .contains("DataAccessResourceFailureException")
        .doesNotContain("db-host");
  }

  @Test
  void anAuditFailureDuringTheFailureReportIsSwallowedToo() {
    when(settings.findAll()).thenThrow(new DataAccessResourceFailureException("down"));
    when(auditEvents.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

    assertThatCode(() -> service(SecretWriteFormat.V2).reEncryptOnStartup())
        .doesNotThrowAnyException();
  }

  @Test
  void nothingToDoRecordsNothing() {
    when(settings.findAll()).thenReturn(List.of());
    when(oidcProviders.findStoredClientSecrets()).thenReturn(List.of());

    service(SecretWriteFormat.V2).reEncryptOnStartup();

    verify(auditEvents, never()).save(any());
  }
}
