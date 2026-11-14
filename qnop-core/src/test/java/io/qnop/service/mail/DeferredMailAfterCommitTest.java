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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.observability.metrics.MailMeters;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The after-commit contract of {@link MailService#sendMailFromTemplateAfterCommit} (issue #903),
 * with the real event machinery: a committed transaction sends, a rolled-back one sends nothing,
 * and outside a transaction the mail still goes out. {@code @Async} is not enabled here, so the
 * listener runs inline and the outcome can be asserted directly.
 */
class DeferredMailAfterCommitTest {

  private static final Map<String, Object> VARS = Map.of("siteName", "qnop");

  private AnnotationConfigApplicationContext context;
  private MailTemplateService templates;
  private MailService mailService;
  private TransactionTemplate tx;

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    MailSenderProvider senderProvider() {
      return mock(MailSenderProvider.class); // no SMTP: a send renders, then is Skipped
    }

    @Bean
    MailTemplateService templates() {
      return mock(MailTemplateService.class);
    }

    @Bean
    MailService mailService(
        MailSenderProvider senderProvider,
        MailTemplateService templates,
        org.springframework.context.ApplicationEventPublisher events) {
      return new MailService(
          senderProvider, templates, events, new MailMeters(new SimpleMeterRegistry()));
    }

    @Bean
    DeferredMailListener listener(MailService mailService) {
      return new DeferredMailListener(mailService);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return new NoResourceTransactionManager();
    }
  }

  /** Transaction boundaries and synchronisation without any resource behind them. */
  static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigApplicationContext(Config.class);
    templates = context.getBean(MailTemplateService.class);
    mailService = context.getBean(MailService.class);
    tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  private void queue() {
    mailService.sendMailFromTemplateAfterCommit(
        MailTemplateKey.REGISTRATION_ATTEMPT, "to@example.com", VARS, "en");
  }

  @Test
  @DisplayName("nothing is rendered or sent before the commit; the commit sends it")
  void sendsAfterCommit() {
    tx.executeWithoutResult(
        status -> {
          queue();
          verifyNoInteractions(templates);
        });

    verify(templates).render(eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq(VARS), eq("en"));
  }

  @Test
  @DisplayName("a rolled-back transaction sends nothing")
  void rollbackSendsNothing() {
    tx.executeWithoutResult(
        status -> {
          queue();
          status.setRollbackOnly();
        });

    verify(templates, never()).render(any(), any(), any());
  }

  @Test
  @DisplayName("outside a transaction the mail still goes out")
  void sendsWithoutTransaction() {
    queue();

    verify(templates).render(eq(MailTemplateKey.REGISTRATION_ATTEMPT), eq(VARS), eq("en"));
  }
}
