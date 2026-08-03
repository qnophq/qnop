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
package io.qnop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * How far the daily digest has got for one recipient (issue #680).
 *
 * <p>Two fields because they answer different questions. {@code lastSentAt} is when the last digest
 * went out; {@code coveredThrough} is the newest {@code createdAt} it actually included.
 *
 * <p>Both are instants, never a local date, per ADR-0041 — and that is not only the house rule: a
 * stored local date would be read against whatever timezone the recipient has <em>now</em>, so
 * somebody who travels would get two digests in a day or none. The instant plus their current zone
 * always answers "have they had today's yet" consistently.
 *
 * <p>{@code coveredThrough} is deliberately not the run's timestamp: a notification written while
 * the digest was being assembled would then fall between the query and the update, and would never
 * be sent by any run.
 */
@Entity
@Table(name = "notification_digest")
public class NotificationDigest {

  @Id
  @Column(name = "recipient_id", nullable = false, updatable = false)
  private UUID recipientId;

  @Column(name = "last_sent_at", nullable = false)
  private Instant lastSentAt;

  @Column(name = "covered_through", nullable = false)
  private Instant coveredThrough;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected NotificationDigest() {
    // JPA
  }

  public NotificationDigest(UUID recipientId, Instant lastSentAt, Instant coveredThrough) {
    this.recipientId = recipientId;
    this.lastSentAt = lastSentAt;
    this.coveredThrough = coveredThrough;
  }

  public UUID getRecipientId() {
    return recipientId;
  }

  public Instant getLastSentAt() {
    return lastSentAt;
  }

  public Instant getCoveredThrough() {
    return coveredThrough;
  }

  /** Advances the watermark after a digest went out. */
  public void advance(Instant sentAt, Instant coveredThrough) {
    this.lastSentAt = sentAt;
    this.coveredThrough = coveredThrough;
  }
}
