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

import io.qnop.service.mail.MailService.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends {@link DeferredTemplateMail}s (issue #903): {@code AFTER_COMMIT} so a rolled-back request
 * mails nobody, {@code fallbackExecution} so a publish outside a transaction still sends, and
 * {@code @Async} on the bounded {@code mailDispatchExecutor} so neither rendering nor SMTP latency
 * sits in the request thread. Mail stays best-effort: a failed send is logged (never the address,
 * issue #659) and swallowed.
 */
@Component
public class DeferredMailListener {

  private static final Logger log = LoggerFactory.getLogger(DeferredMailListener.class);

  private final MailService mailService;

  public DeferredMailListener(MailService mailService) {
    this.mailService = mailService;
  }

  @Async("mailDispatchExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void on(DeferredTemplateMail mail) {
    try {
      SendResult result =
          mailService.sendMailFromTemplate(mail.key(), mail.to(), mail.vars(), mail.locale());
      if (result instanceof SendResult.Failed failed) {
        log.warn("deferred {} mail was not sent: {}", mail.key().key(), failed.reason());
      }
    } catch (RuntimeException ex) {
      log.warn("deferred {} mail failed", mail.key().key(), ex);
    }
  }
}
