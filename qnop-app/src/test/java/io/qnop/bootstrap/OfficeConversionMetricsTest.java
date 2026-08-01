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
package io.qnop.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qnop.service.convert.OfficeConverter;
import io.qnop.service.convert.OfficeConverterProperties;
import io.qnop.service.convert.ThrottledOfficeConverter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The conversion-pressure gauges (issue #651).
 *
 * <p>Asserted against a converter that is actually held busy rather than a mock, because the number
 * that matters — how many are running — is a property of the semaphore, and a mocked answer would
 * only prove the test's own arithmetic.
 */
class OfficeConversionMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ExecutorService threads = Executors.newCachedThreadPool();

  @AfterEach
  void tearDown() {
    threads.shutdownNow();
  }

  @Test
  @Timeout(30)
  @DisplayName("reports the running conversion, the queued one and the ceiling")
  void reportsPressure() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    OfficeConverter delegate =
        new OfficeConverter() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public byte[] toPdf(byte[] source, String sourceExtension) {
            running.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "%PDF".getBytes(StandardCharsets.UTF_8);
          }
        };
    ThrottledOfficeConverter converter =
        new ThrottledOfficeConverter(
            delegate, new OfficeConverterProperties(null, null, 1, Duration.ofSeconds(5)));
    new OfficeConversionMetrics(converter).bindTo(registry);

    assertThat(gauge("active")).isZero();

    byte[] source = "a document".getBytes(StandardCharsets.UTF_8);
    threads.submit(() -> converter.toPdf(source, "docx"));
    assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
    threads.submit(() -> converter.toPdf(source, "docx"));
    // The second caller has to reach the semaphore before it counts as waiting;
    // that is a thread start away, not a conversion away.
    Thread.sleep(250);

    assertThat(gauge("active")).isEqualTo(1.0);
    assertThat(gauge("waiting")).isEqualTo(1.0);
    assertThat(registry.find("qnop.office.conversion.limit").gauge().value()).isEqualTo(1.0);

    release.countDown();
  }

  private double gauge(String state) {
    Gauge gauge = registry.find("qnop.office.conversions").tag("state", state).gauge();
    assertThat(gauge).as("gauge qnop.office.conversions{state=%s}", state).isNotNull();
    return gauge.value();
  }
}
