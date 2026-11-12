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
package io.qnop.service.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.service.mail.MailService.SendResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeferredMailListenerTest {

  @Mock private MailService mailService;

  private static final DeferredTemplateMail MAIL =
      new DeferredTemplateMail(
          MailTemplateKey.REGISTRATION_ATTEMPT,
          "jane@example.com",
          Map.of("siteName", "qnop", "actionUrl", "https://qnop.example/login"),
          "de");

  @Test
  @DisplayName("a queued mail is rendered and sent with its key, recipient, variables and language")
  void sendsTheQueuedMail() {
    when(mailService.sendMailFromTemplate(MAIL.key(), MAIL.to(), MAIL.vars(), MAIL.locale()))
        .thenReturn(new SendResult.Sent(MAIL.to()));

    new DeferredMailListener(mailService).on(MAIL);

    verify(mailService).sendMailFromTemplate(MAIL.key(), MAIL.to(), MAIL.vars(), MAIL.locale());
  }

  @Test
  @DisplayName("a failing send never propagates — mail stays best-effort")
  void swallowsFailures() {
    when(mailService.sendMailFromTemplate(MAIL.key(), MAIL.to(), MAIL.vars(), MAIL.locale()))
        .thenThrow(new IllegalStateException("smtp down"));

    assertThatCode(() -> new DeferredMailListener(mailService).on(MAIL)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a Failed result is tolerated as well")
  void toleratesFailedResult() {
    when(mailService.sendMailFromTemplate(MAIL.key(), MAIL.to(), MAIL.vars(), MAIL.locale()))
        .thenReturn(new SendResult.Failed("nope"));

    assertThatCode(() -> new DeferredMailListener(mailService).on(MAIL)).doesNotThrowAnyException();
  }
}
