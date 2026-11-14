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

import io.qnop.observability.metrics.MailMeters;
import io.qnop.observability.metrics.MailMeters.Outcome;
import io.qnop.service.ControlCharacters;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends mail via the runtime SMTP transport (issue #19). {@link #sendMail} <strong>never
 * throws</strong>: it returns a sealed {@link SendResult} describing the outcome — {@code Sent},
 * {@code Skipped} (SMTP not configured), or {@code Failed} (delivery error). Callers treat mail as
 * best-effort and decide how to react (e.g. surface a "verification email could not be sent"
 * notice). Messages with an HTML body are sent as multipart/alternative.
 */
@Service
public class MailService {

  private static final Logger log = LoggerFactory.getLogger(MailService.class);

  private final MailSenderProvider senderProvider;
  private final MailTemplateService templates;
  private final ApplicationEventPublisher events;
  private final MailMeters meters;

  public MailService(
      MailSenderProvider senderProvider,
      MailTemplateService templates,
      ApplicationEventPublisher events,
      MailMeters meters) {
    this.senderProvider = senderProvider;
    this.templates = templates;
    this.events = events;
    this.meters = meters;
  }

  /** Sends a message; returns the outcome without throwing. */
  public SendResult sendMail(String to, String subject, String bodyPlain, String bodyHtml) {
    return measured(null, () -> deliver(to, subject, bodyPlain, bodyHtml));
  }

  private SendResult deliver(String to, String subject, String bodyPlain, String bodyHtml) {
    JavaMailSender sender = senderProvider.current();
    if (sender == null) {
      return new SendResult.Skipped("SMTP is not configured");
    }
    boolean multipart = bodyHtml != null && !bodyHtml.isBlank();
    try {
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
      String from = senderProvider.from();
      if (from != null && !from.isBlank()) {
        String fromName = senderProvider.fromName();
        if (fromName != null && !fromName.isBlank()) {
          helper.setFrom(from, fromName);
        } else {
          helper.setFrom(from);
        }
      }
      helper.setTo(to);
      // Whatever the template and its values produced, a subject is one header line
      // (issue #909): control characters, CR/LF included, become spaces.
      helper.setSubject(ControlCharacters.toSpaces(subject));
      if (multipart) {
        helper.setText(bodyPlain, bodyHtml);
      } else {
        helper.setText(bodyPlain, false);
      }
      sender.send(message);
      return new SendResult.Sent(to);
    } catch (Exception e) {
      // Never the address (issue #659): a failed send would otherwise write a
      // recipient into a log file on every SMTP hiccup. The request or job in the
      // diagnostic context is enough to find which send this was.
      log.warn("Failed to send mail: {}", e.getMessage());
      return new SendResult.Failed(e.getMessage());
    }
  }

  /** Renders {@code key} for {@code locale} with {@code vars}, then sends. Never throws. */
  public SendResult sendMailFromTemplate(
      MailTemplateKey key, String to, Map<String, Object> vars, String locale) {
    return measured(
        key,
        () -> {
          RenderedMail mail;
          try {
            mail = templates.render(key, vars, locale);
          } catch (RuntimeException e) {
            log.warn("Failed to render mail template {}: {}", key, e.getMessage());
            return new SendResult.Failed("template render failed: " + e.getMessage());
          }
          return deliver(to, mail.subject(), mail.bodyPlain(), mail.bodyHtml());
        });
  }

  /**
   * Times one attempt and records how it ended (issue #962), tagged with the template and nothing
   * about the recipient.
   */
  private SendResult measured(MailTemplateKey template, Supplier<SendResult> attempt) {
    long started = System.nanoTime();
    SendResult result = attempt.get();
    Outcome outcome =
        switch (result) {
          case SendResult.Sent sent -> Outcome.SENT;
          case SendResult.Skipped skipped -> Outcome.SKIPPED;
          case SendResult.Failed failed -> Outcome.FAILED;
        };
    meters.sent(template, outcome, Duration.ofNanos(System.nanoTime() - started));
    return result;
  }

  /**
   * Queues a template mail to be rendered and sent off the request thread once the surrounding
   * transaction has committed (issue #903) — immediately and still asynchronously when there is no
   * transaction. For flows whose caller must learn nothing from how long the request took (the
   * anti-enumeration registration and password-reset requests): SMTP latency would otherwise reveal
   * which branch ran. A rolled-back transaction sends nothing. The outcome is only logged, since
   * nobody is waiting for it; callers that need the {@link SendResult} use {@link
   * #sendMailFromTemplate}.
   */
  public void sendMailFromTemplateAfterCommit(
      MailTemplateKey key, String to, Map<String, Object> vars, String locale) {
    events.publishEvent(new DeferredTemplateMail(key, to, Map.copyOf(vars), locale));
  }

  /** The outcome of a send attempt. */
  public sealed interface SendResult
      permits SendResult.Sent, SendResult.Skipped, SendResult.Failed {

    /** Delivery handed off to the SMTP server. */
    record Sent(String recipient) implements SendResult {}

    /** No delivery attempted because SMTP is not configured. */
    record Skipped(String reason) implements SendResult {}

    /** Delivery attempted but failed; {@code reason} carries the (non-sensitive) cause. */
    record Failed(String reason) implements SendResult {}
  }
}
