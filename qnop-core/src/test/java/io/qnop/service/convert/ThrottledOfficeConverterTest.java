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
package io.qnop.service.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The limit on how many conversions run at once (issue #651).
 *
 * <p>The delegate is faked rather than real, for the reason the seam exists at all: an office suite
 * is not something a test may assume. What is asserted is the gate itself — that a third caller
 * waits while two run, that it proceeds the moment a slot frees, that a caller which waited too
 * long is refused in a way the job queue will retry, and that no path leaks a permit.
 */
class ThrottledOfficeConverterTest {

  private static final byte[] SOURCE = "a document".getBytes(StandardCharsets.UTF_8);

  /** How long a test may wait for something that should happen almost immediately. */
  private static final Duration SOON = Duration.ofSeconds(5);

  /** How long a test waits to convince itself something does <em>not</em> happen. */
  private static final Duration BRIEFLY = Duration.ofMillis(250);

  private final ExecutorService threads = Executors.newCachedThreadPool();

  @AfterEach
  void tearDown() {
    threads.shutdownNow();
  }

  /** Blocks inside {@code toPdf} until released, counting how many are inside at once. */
  private static final class BlockingConverter implements OfficeConverter {
    private final CountDownLatch entered;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private volatile RuntimeException failure;

    /**
     * @param expectedCallers how many must be inside before {@link #entered} opens
     */
    BlockingConverter(int expectedCallers) {
      this.entered = new CountDownLatch(expectedCallers);
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public byte[] toPdf(byte[] source, String sourceExtension) {
      highWaterMark.accumulateAndGet(inside.incrementAndGet(), Math::max);
      entered.countDown();
      try {
        if (failure != null) {
          throw failure;
        }
        release.await(SOON.toSeconds(), TimeUnit.SECONDS);
        return ("converted:" + sourceExtension).getBytes(StandardCharsets.UTF_8);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new OfficeConversionException("interrupted", e);
      } finally {
        inside.decrementAndGet();
      }
    }
  }

  private static ThrottledOfficeConverter throttled(
      OfficeConverter delegate, int maxConcurrent, Duration maxWait) {
    return new ThrottledOfficeConverter(
        delegate, new OfficeConverterProperties(null, null, maxConcurrent, maxWait));
  }

  @Test
  @Timeout(30)
  @DisplayName("holds callers past the limit outside, then lets one in per freed slot")
  void boundsHowManyRunAtOnce() throws Exception {
    BlockingConverter delegate = new BlockingConverter(2);
    ThrottledOfficeConverter converter = throttled(delegate, 2, SOON);

    List<Future<byte[]>> conversions = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      conversions.add(threads.submit(() -> converter.toPdf(SOURCE, "docx")));
    }

    assertThat(delegate.entered.await(SOON.toSeconds(), TimeUnit.SECONDS)).isTrue();
    // The third must still be outside. That is the whole point: a free request thread
    // is not a reason to start a third office process.
    Thread.sleep(BRIEFLY.toMillis());
    assertThat(delegate.inside.get()).isEqualTo(2);

    delegate.release.countDown();

    for (Future<byte[]> conversion : conversions) {
      assertThat(
              new String(
                  conversion.get(SOON.toSeconds(), TimeUnit.SECONDS), StandardCharsets.UTF_8))
          .isEqualTo("converted:docx");
    }
    assertThat(delegate.highWaterMark.get()).isLessThanOrEqualTo(2);
  }

  @Test
  @Timeout(30)
  @DisplayName("refuses a caller that waited too long, transiently so the queue retries it")
  void refusesAfterTheWait() throws Exception {
    BlockingConverter delegate = new BlockingConverter(1);
    ThrottledOfficeConverter converter = throttled(delegate, 1, Duration.ZERO);

    threads.submit(() -> converter.toPdf(SOURCE, "docx"));
    assertThat(delegate.entered.await(SOON.toSeconds(), TimeUnit.SECONDS)).isTrue();

    // Not permanent: the export answers 503 and an ingest job comes back under the
    // queue's backoff. Permanent would fail a version over one busy minute.
    assertThatThrownBy(() -> converter.toPdf(SOURCE, "docx"))
        .isInstanceOf(OfficeConverterBusyException.class)
        .satisfies(
            thrown -> assertThat(((OfficeConversionException) thrown).isPermanent()).isFalse());
  }

  @Test
  @Timeout(30)
  @DisplayName("returns the slot when the conversion fails")
  void releasesTheSlotOnFailure() {
    BlockingConverter delegate = new BlockingConverter(1);
    delegate.failure = new OfficeConversionException("the converter exited with status 1");
    ThrottledOfficeConverter converter = throttled(delegate, 1, Duration.ZERO);

    assertThatThrownBy(() -> converter.toPdf(SOURCE, "docx"))
        .isInstanceOf(OfficeConversionException.class);

    // A leaked permit would surface only once the limit is exhausted — hours later
    // and nowhere near the failure that caused it.
    assertThatThrownBy(() -> converter.toPdf(SOURCE, "docx"))
        .isInstanceOf(OfficeConversionException.class)
        .isNotInstanceOf(OfficeConverterBusyException.class);
  }

  @Test
  @Timeout(30)
  @DisplayName("asking whether a converter exists never queues behind a conversion")
  void availabilityIsNotThrottled() throws Exception {
    BlockingConverter delegate = new BlockingConverter(1);
    ThrottledOfficeConverter converter = throttled(delegate, 1, SOON);

    threads.submit(() -> converter.toPdf(SOURCE, "docx"));
    assertThat(delegate.entered.await(SOON.toSeconds(), TimeUnit.SECONDS)).isTrue();

    // Config, the upload check and the format list all ask this on ordinary requests
    // that convert nothing; queueing them behind a conversion would spread one slow
    // export across pages that have nothing to do with it.
    assertThat(converter.isAvailable()).isTrue();
  }

  @Test
  @Timeout(30)
  @DisplayName("passes the document through untouched when a slot is free")
  void delegatesWhenNotContended() {
    OfficeConverter delegate =
        new OfficeConverter() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public byte[] toPdf(byte[] source, String sourceExtension) {
            return (new String(source, StandardCharsets.UTF_8) + " as " + sourceExtension)
                .getBytes(StandardCharsets.UTF_8);
          }
        };

    byte[] result = throttled(delegate, 2, SOON).toPdf(SOURCE, "docx");

    assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("a document as docx");
  }

  @Test
  @Timeout(30)
  @DisplayName("a waiter that is interrupted gives up and stays interrupted")
  void interruptedWaiterGivesUp() throws Exception {
    BlockingConverter delegate = new BlockingConverter(1);
    ThrottledOfficeConverter converter = throttled(delegate, 1, Duration.ofMinutes(5));

    threads.submit(() -> converter.toPdf(SOURCE, "docx"));
    assertThat(delegate.entered.await(SOON.toSeconds(), TimeUnit.SECONDS)).isTrue();

    CountDownLatch done = new CountDownLatch(1);
    AtomicInteger outcome = new AtomicInteger();
    Thread waiter =
        new Thread(
            () -> {
              try {
                converter.toPdf(SOURCE, "docx");
              } catch (OfficeConversionException e) {
                // 1 = refused and the flag survived, which is what a shutdown needs:
                // an interrupt that is swallowed leaves the thread looking healthy.
                outcome.set(Thread.currentThread().isInterrupted() ? 1 : 2);
              } finally {
                done.countDown();
              }
            });
    waiter.start();
    // Long enough that the waiter is parked on the semaphore rather than still on its
    // way there; interrupting too early would test nothing.
    Thread.sleep(BRIEFLY.toMillis());
    waiter.interrupt();

    assertThat(done.await(SOON.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(outcome.get()).isEqualTo(1);
  }
}
