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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.observability.metrics.MailMeters;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  @Mock private MailSenderProvider senderProvider;
  @Mock private MailTemplateService templates;
  @Mock private JavaMailSender sender;
  @Mock private ApplicationEventPublisher events;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private MailService service;

  @BeforeEach
  void setUp() {
    service = new MailService(senderProvider, templates, events, new MailMeters(registry));
  }

  @Test
  @DisplayName("returns Skipped when SMTP is not configured")
  void skippedWhenNotConfigured() {
    when(senderProvider.current()).thenReturn(null);

    SendResult result = service.sendMail("to@example.com", "s", "p", null);

    assertThat(result).isInstanceOf(SendResult.Skipped.class);
    assertThat(sends(MailMeters.ADHOC, "skipped")).isEqualTo(1);
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
    // A message without a template is tagged as such, never with anything about its recipient.
    assertThat(sends(MailMeters.ADHOC, "sent")).isEqualTo(1);
    assertThat(registry.find(MailMeters.SEND).timers())
        .isNotEmpty()
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("template", "outcome"));
  }

  @Test
  @DisplayName("the subject never carries CR/LF or other control characters")
  void subjectIsOneLine() throws Exception {
    when(senderProvider.current()).thenReturn(sender);
    when(senderProvider.from()).thenReturn("from@qnop.example");
    MimeMessage mime = new MimeMessage((Session) null);
    when(sender.createMimeMessage()).thenReturn(mime);

    service.sendMail("to@example.com", "Review: Plan\r\nBcc: x@example.com\u0000", "Body", null);

    assertThat(mime.getSubject())
        .startsWith("Review: Plan  Bcc: x@example.com")
        .doesNotContain("\r", "\n", "\u0000");
    assertThat(mime.getHeader("Bcc")).isNull();
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
    assertThat(sends(MailMeters.ADHOC, "failed")).isEqualTo(1);
  }

  @Test
  @DisplayName("returns Failed when template rendering fails, without attempting delivery")
  void failedWhenRenderThrows() {
    when(templates.render(any(), any(), any())).thenThrow(new RuntimeException("missing var"));

    SendResult result =
        service.sendMailFromTemplate(
            MailTemplateKey.PASSWORD_RESET, "to@example.com", Map.of(), "en");

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    // One sample per attempt, under the template's name: a render failure is not also an
    // ad-hoc send.
    assertThat(sends("password_reset", "failed")).isEqualTo(1);
    assertThat(registry.find(MailMeters.SEND).timers()).hasSize(1);
  }

  @Test
  @DisplayName("a template mail is measured once, under its template (issue #962)")
  void templateMailIsMeasuredUnderItsTemplate() {
    when(templates.render(any(), any(), any()))
        .thenReturn(new RenderedMail("Subject", "Body", null));
    when(senderProvider.current()).thenReturn(sender);
    when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

    service.sendMailFromTemplate(MailTemplateKey.TEAM_INVITATION, "to@example.com", Map.of(), "en");

    assertThat(sends("team_invitation", "sent")).isEqualTo(1);
    assertThat(registry.find(MailMeters.SEND).timers()).hasSize(1);
  }

  @Test
  @DisplayName("an attempt that throws before delivery is still measured, as failed")
  void throwingAttemptIsMeasured() {
    when(senderProvider.current()).thenThrow(new IllegalStateException("settings unreadable"));

    assertThatThrownBy(() -> service.sendMail("to@example.com", "s", "p", null))
        .isInstanceOf(IllegalStateException.class);

    assertThat(sends(MailMeters.ADHOC, "failed")).isEqualTo(1);
  }

  /** How many send attempts were measured for this template and outcome (issue #962). */
  private long sends(String template, String outcome) {
    var timer =
        registry.find(MailMeters.SEND).tag("template", template).tag("outcome", outcome).timer();
    return timer == null ? 0 : timer.count();
  }

  @Test
  @DisplayName("sendMailFromTemplateAfterCommit only publishes the mail, never sends inline (#903)")
  void afterCommitPublishesInsteadOfSending() {
    service.sendMailFromTemplateAfterCommit(
        MailTemplateKey.PASSWORD_RESET, "to@example.com", Map.of("siteName", "qnop"), "de");

    verify(events)
        .publishEvent(
            new DeferredTemplateMail(
                MailTemplateKey.PASSWORD_RESET,
                "to@example.com",
                Map.of("siteName", "qnop"),
                "de"));
    verifyNoInteractions(senderProvider, templates);
  }

  @Test
  @DisplayName("the SMTP master switch still silences team-invitation mail (#948)")
  void masterSwitchSilencesTeamInvitationMail() {
    // The team-invitation mail is exempt from the review-mail switch, not from the mail
    // layer's own one: with smtp.enabled off (or no host) there is no sender at all.
    when(senderProvider.current()).thenReturn(null);
    when(templates.render(any(), any(), any()))
        .thenReturn(new RenderedMail("subject", "plain", "<p>html</p>"));
    io.qnop.repository.UserSettingRepository userSettings =
        org.mockito.Mockito.mock(io.qnop.repository.UserSettingRepository.class);
    io.qnop.service.ApplicationSettingsService settings =
        org.mockito.Mockito.mock(io.qnop.service.ApplicationSettingsService.class);
    io.qnop.service.review.MailNotificationSink sink =
        new io.qnop.service.review.MailNotificationSink(userSettings, settings, service);
    io.qnop.entity.User recipient =
        io.qnop.entity.User.internal("Ivy", "ivy@example.com", "ivy", "h");
    io.qnop.service.review.ReviewNotificationIntent intent =
        io.qnop.service.review.ReviewNotificationIntent.to(
                recipient,
                io.qnop.entity.NotificationType.TEAM_INVITATION,
                MailTemplateKey.TEAM_INVITATION)
            .mailLocale("en")
            .build();

    sink.deliver(intent);

    verify(senderProvider).current();
    verifyNoInteractions(sender);
  }
}
