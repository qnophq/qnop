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

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how many conversions run at once (issue #651).
 *
 * <p>A conversion is an office suite: a cold start against a fresh profile, a temp directory, and
 * that process's whole resident set. One is expensive and known to be; N at once is a container
 * meeting its memory limit, or conversions slowing each other past the 60s deadline so that
 * requests fail which would have succeeded had they simply queued. Nothing bounded N — the export
 * path runs <em>synchronously in the request thread</em>, so the real limit was the servlet thread
 * pool, and a handful of reviewers exporting a large review at the same moment is an ordinary
 * Monday.
 *
 * <p>A decorator rather than a semaphore inside {@link LibreOfficeConverter}: it is testable
 * without an office suite installed, and the limit then holds for any converter this ever runs, not
 * just this one.
 *
 * <p>Two choices worth stating, both deliberate (ADR-0055):
 *
 * <ul>
 *   <li><b>Waiting is bounded.</b> Queueing is kinder than failing right up to the point where a
 *       request thread is held so long the caller has given up. Past {@code qnop.office.max-wait} a
 *       refusal it can retry is the better answer — and because it is transient, an ingest job just
 *       comes back later while an export answers 503.
 *   <li><b>The limit is per instance.</b> A semaphore bounds this JVM. Two instances behind a load
 *       balancer may each run their maximum; bounding a deployment needs coordination that ShedLock
 *       (ADR-0029) is not built for, and it is out of scope here.
 * </ul>
 *
 * <p>Only conversions queue. {@link #isAvailable()} is asked by config, the format list and every
 * upload check, on requests that convert nothing.
 */
public class ThrottledOfficeConverter implements OfficeConverter {

  private static final Logger log = LoggerFactory.getLogger(ThrottledOfficeConverter.class);

  /** A wait worth mentioning; below it the queue did its job and nobody needs a line about it. */
  private static final Duration NOTEWORTHY_WAIT = Duration.ofSeconds(1);

  private final OfficeConverter delegate;
  private final int limit;
  private final Duration maxWait;

  /** Fair, so a steady stream of exports cannot starve the one that has waited longest. */
  private final Semaphore slots;

  private final AtomicInteger waiting = new AtomicInteger();

  public ThrottledOfficeConverter(OfficeConverter delegate, OfficeConverterProperties properties) {
    this.delegate = delegate;
    this.limit = properties.maxConcurrent();
    this.maxWait = properties.maxWait();
    this.slots = new Semaphore(limit, true);
  }

  @Override
  public boolean isAvailable() {
    return delegate.isAvailable();
  }

  @Override
  public byte[] toPdf(byte[] source, String sourceExtension) {
    long startedWaiting = System.nanoTime();
    if (!acquire()) {
      log.warn(
          "Refused a conversion: all {} slots busy for {} (waiting: {})",
          limit,
          maxWait,
          waiting.get());
      throw new OfficeConverterBusyException(limit, maxWait);
    }
    try {
      long waited = (System.nanoTime() - startedWaiting) / 1_000_000;
      if (waited >= NOTEWORTHY_WAIT.toMillis()) {
        // Not a failure, but the shape of one to come: if this appears often, the
        // instance is running at its conversion limit and wants a bigger one.
        log.info("Waited {} ms for one of {} conversion slots", waited, limit);
      }
      return delegate.toPdf(source, sourceExtension);
    } finally {
      // In a finally and not after the call: a permit lost to a failed conversion
      // would shrink the limit silently and only surface hours later, as exports
      // that queue for no visible reason.
      slots.release();
    }
  }

  private boolean acquire() {
    waiting.incrementAndGet();
    try {
      return slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OfficeConversionException("interrupted while waiting for a conversion slot", e);
    } finally {
      waiting.decrementAndGet();
    }
  }

  /** How many conversions are running right now — for the gauges (ADR-0037). */
  public int active() {
    return limit - slots.availablePermits();
  }

  /** How many callers are queued for a slot right now. */
  public int waiting() {
    return waiting.get();
  }

  /** The configured ceiling, so a dashboard can show how close to it the instance runs. */
  public int limit() {
    return limit;
  }
}
