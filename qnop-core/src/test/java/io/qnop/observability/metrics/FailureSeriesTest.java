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
package io.qnop.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The failure series exist at zero before the first failure (issue #984).
 *
 * <p>A series that is created by its first event appears at its final value: {@code increase()} and
 * {@code rate()} need an earlier sample to rise from, so a burst that falls into one scrape
 * interval is invisible to them, and so to the alert rules. Each case here therefore checks two
 * things: the series is there at 0 before anything happened, and one failure makes it 1 — the rise
 * a rule sees.
 */
class FailureSeriesTest {

  private static final Duration ONCE = Duration.ofMillis(1);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("object store: every operation's error series")
  void storageErrors() {
    StorageMeters meters = new StorageMeters(registry);

    for (StorageMeters.Operation operation : StorageMeters.Operation.values()) {
      String name = MeterTags.of(operation);
      assertThat(timerCount(StorageMeters.OPERATION, "operation", name, "outcome", "error"))
          .as("%s errors", name)
          .isZero();
    }

    meters.called(StorageMeters.Operation.PUT, StorageMeters.Outcome.ERROR, ONCE);
    assertThat(timerCount(StorageMeters.OPERATION, "operation", "put", "outcome", "error"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("sign-in: every login outcome but success, per method, and every refresh refusal")
  void signInRefusals() {
    AuthMeters meters = new AuthMeters(registry);

    for (String outcome : new String[] {"rejected", "throttled", "error"}) {
      assertThat(counter(AuthMeters.LOGIN, "method", "local", "outcome", outcome).count())
          .as("local %s logins", outcome)
          .isZero();
    }
    for (String outcome : new String[] {"rejected", "error"}) {
      assertThat(counter(AuthMeters.LOGIN, "method", "oidc", "outcome", outcome).count())
          .as("oidc %s logins", outcome)
          .isZero();
    }
    // A single sign-on is never throttled by qnop, so that series would never be recorded.
    assertThat(
            registry
                .find(AuthMeters.LOGIN)
                .tag("method", "oidc")
                .tag("outcome", "throttled")
                .counter())
        .isNull();
    for (String outcome : new String[] {"missing", "reused", "unknown"}) {
      assertThat(counter(AuthMeters.REFRESH, "outcome", outcome).count())
          .as("%s refreshes", outcome)
          .isZero();
    }
    // Success is not a failure series: it appears with its first event, as before.
    assertThat(registry.find(AuthMeters.REFRESH).tag("outcome", "success").counter()).isNull();

    meters.refresh(AuthMeters.RefreshOutcome.REUSED);
    meters.login(AuthMeters.Method.LOCAL, AuthMeters.LoginOutcome.REJECTED);
    assertThat(counter(AuthMeters.REFRESH, "outcome", "reused").count()).isEqualTo(1);
    assertThat(counter(AuthMeters.LOGIN, "method", "local", "outcome", "rejected").count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("rate limits: a declared scope's refusal series")
  void rateLimitRefusals() {
    AuthMeters meters = new AuthMeters(registry);

    meters.rateLimitDeclared("login");
    assertThat(counter(AuthMeters.RATE_LIMITED, "scope", "login").count()).isZero();

    meters.rateLimited("login");
    assertThat(counter(AuthMeters.RATE_LIMITED, "scope", "login").count()).isEqualTo(1);
  }

  @Test
  @DisplayName("mail: a declared template's failure series, and the ad-hoc message's")
  void mailFailures() {
    MailMeters meters = new MailMeters(registry);

    meters.templateDeclared(Template.WELCOME);
    meters.templateDeclared(null);
    assertThat(timerCount(MailMeters.SEND, "template", "welcome", "outcome", "failed")).isZero();
    assertThat(timerCount(MailMeters.SEND, "template", MailMeters.ADHOC, "outcome", "failed"))
        .isZero();

    meters.sent(Template.WELCOME, MailMeters.Outcome.FAILED, ONCE);
    assertThat(timerCount(MailMeters.SEND, "template", "welcome", "outcome", "failed"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("extraction: rejected and failed versions of every format")
  void extractionFailures() {
    IngestMeters meters = new IngestMeters(registry);

    // "other" too: a format an extension adds is extracted under it.
    for (String format : new String[] {"pdf", "docx", "other"}) {
      for (String outcome : new String[] {"rejected", "error"}) {
        assertThat(timerCount(IngestMeters.EXTRACTION, "format", format, "outcome", outcome))
            .as("%s %s", format, outcome)
            .isZero();
      }
    }

    meters.extracted(IngestMeters.Format.DOCX, IngestMeters.Outcome.ERROR, ONCE);
    assertThat(timerCount(IngestMeters.EXTRACTION, "format", "docx", "outcome", "error"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("conversion: rejected and failed conversions")
  void conversionFailures() {
    ConversionMeters meters = new ConversionMeters(registry);

    assertThat(timerCount(ConversionMeters.CONVERSION, "outcome", "rejected")).isZero();
    assertThat(timerCount(ConversionMeters.CONVERSION, "outcome", "failure")).isZero();

    meters.converted(ConversionMeters.Outcome.FAILURE, ONCE);
    assertThat(timerCount(ConversionMeters.CONVERSION, "outcome", "failure")).isEqualTo(1);
  }

  @Test
  @DisplayName("review events: listener failures of every event type")
  void listenerFailures() {
    ReviewEventMeters meters = new ReviewEventMeters(registry);

    for (ReviewEventMeters.Type type : ReviewEventMeters.Type.values()) {
      assertThat(counter(ReviewEventMeters.LISTENER_FAILURES, "type", MeterTags.of(type)).count())
          .as("%s listener failures", type)
          .isZero();
    }

    meters.listenerFailed(ReviewEventMeters.Type.COMMENT_ADDED);
    assertThat(counter(ReviewEventMeters.LISTENER_FAILURES, "type", "comment_added").count())
        .isEqualTo(1);
  }

  private long timerCount(String name, String... tags) {
    Timer timer = registry.find(name).tags(tags).timer();
    assertThat(timer).as("%s with %s", name, String.join(",", tags)).isNotNull();
    return timer.count();
  }

  private Counter counter(String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    assertThat(counter).as("%s with %s", name, String.join(",", tags)).isNotNull();
    return counter;
  }

  /** A stand-in for a mail catalogue entry. */
  private enum Template {
    WELCOME
  }
}
