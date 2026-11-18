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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Meters of outgoing mail: how long handing one message to the SMTP server took and how it ended,
 * per template.
 *
 * <p>The template is the catalogue entry, never anything about the recipient (ADR-0065). A message
 * that is not rendered from a template, such as the administrator's test mail, is tagged {@link
 * #ADHOC}.
 */
@Component
public class MailMeters {

  public static final String SEND = "qnop.mail.send";

  /** The template tag of a message that was not rendered from the catalogue. */
  public static final String ADHOC = "adhoc";

  /** How a send attempt ended. */
  public enum Outcome {
    SENT,
    /** No SMTP server is configured, so nothing was attempted. */
    SKIPPED,
    /** The template could not be rendered, or the SMTP server refused or was unreachable. */
    FAILED
  }

  private static final Duration[] BUCKETS = {
    Duration.ofMillis(50),
    Duration.ofMillis(100),
    Duration.ofMillis(250),
    Duration.ofMillis(500),
    Duration.ofSeconds(1),
    Duration.ofMillis(2500),
    Duration.ofSeconds(5),
    Duration.ofSeconds(10),
    Duration.ofSeconds(30)
  };

  private final MeterRegistry registry;

  public MailMeters(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * One send attempt took {@code duration}.
   *
   * @param template the catalogue entry the message was rendered from, or {@code null} for a
   *     message that has none
   */
  public void sent(Enum<?> template, Outcome outcome, Duration duration) {
    Timer.builder(SEND)
        .description("Duration of one attempt to send a mail")
        .tag("template", template == null ? ADHOC : MeterTags.of(template))
        .tag("outcome", MeterTags.of(outcome))
        .serviceLevelObjectives(BUCKETS)
        .register(registry)
        .record(duration);
  }
}
