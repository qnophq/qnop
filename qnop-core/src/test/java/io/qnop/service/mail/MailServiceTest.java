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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.qnop.service.mail.MailService.SendResult;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  @Mock private MailSenderProvider senderProvider;
  @Mock private MailTemplateService templates;
  @Mock private JavaMailSender sender;

  private MailService service;

  @BeforeEach
  void setUp() {
    service = new MailService(senderProvider, templates);
  }

  @Test
  @DisplayName("returns Skipped when SMTP is not configured")
  void skippedWhenNotConfigured() {
    when(senderProvider.current()).thenReturn(null);

    SendResult result = service.sendMail("to@example.com", "s", "p", null);

    assertThat(result).isInstanceOf(SendResult.Skipped.class);
  }

  @Test
  @DisplayName("returns Sent and hands the message to the transport on success")
  void sentOnSuccess() {
    when(senderProvider.current()).thenReturn(sender);
    when(senderProvider.from()).thenReturn("from@qnop.example");
    when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

    SendResult result = service.sendMail("to@example.com", "Subject", "Body", null);

    assertThat(result).isInstanceOf(SendResult.Sent.class);
    verify(sender).send(any(MimeMessage.class));
  }

  @Test
  @DisplayName("uses smtp.from_name as the From display name")
  void usesFromNameAsDisplayName() throws Exception {
    when(senderProvider.current()).thenReturn(sender);
    when(senderProvider.from()).thenReturn("from@qnop.example");
    when(senderProvider.fromName()).thenReturn("qnop Mailer");
    MimeMessage mime = new MimeMessage((Session) null);
    when(sender.createMimeMessage()).thenReturn(mime);

    SendResult result = service.sendMail("to@example.com", "Subject", "Body", null);

    assertThat(result).isInstanceOf(SendResult.Sent.class);
    assertThat(mime.getFrom()).hasSize(1);
    assertThat(mime.getFrom()[0].toString()).contains("qnop Mailer").contains("from@qnop.example");
  }

  @Test
  @DisplayName("returns Failed (never throws) when delivery fails")
  void failedOnDeliveryError() {
    when(senderProvider.current()).thenReturn(sender);
    when(senderProvider.from()).thenReturn("from@qnop.example");
    when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    doThrow(new MailSendException("boom")).when(sender).send(any(MimeMessage.class));

    SendResult result = service.sendMail("to@example.com", "Subject", "Body", null);

    assertThat(result).isInstanceOf(SendResult.Failed.class);
  }

  @Test
  @DisplayName("returns Failed when template rendering fails, without attempting delivery")
  void failedWhenRenderThrows() {
    when(templates.render(any(), any(), any())).thenThrow(new RuntimeException("missing var"));

    SendResult result =
        service.sendMailFromTemplate(
            MailTemplateKey.PASSWORD_RESET, "to@example.com", Map.of(), "en");

    assertThat(result).isInstanceOf(SendResult.Failed.class);
  }
}
