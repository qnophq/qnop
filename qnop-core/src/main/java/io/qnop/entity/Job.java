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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A durable async job (issue #242, ADR-0033). Rows are enqueued transactionally with the triggering
 * write (outbox), claimed by the poll-loop via {@code FOR UPDATE SKIP LOCKED}, and retried with
 * capped backoff. The {@link #type} selects the handler; the {@link #payload} is opaque jsonb the
 * handler parses. Identity is UUIDv7 (Hibernate); {@code @Version} guards concurrent status writes.
 */
@Entity
@Table(name = "job")
public class Job {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "type", nullable = false, length = 64, updatable = false)
  private String type;

  /** Opaque jsonb payload; the handler for {@link #type} deserializes it. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private JobStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false, updatable = false)
  private int maxAttempts;

  @Column(name = "run_after", nullable = false)
  private Instant runAfter;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected Job() {
    // for JPA
  }

  public Job(String type, String payload, int maxAttempts, Instant runAfter) {
    this.type = type;
    this.payload = payload;
    this.status = JobStatus.PENDING;
    this.maxAttempts = maxAttempts;
    this.runAfter = runAfter;
  }

  /** Marks the job completed; clears any prior error. */
  public void markDone() {
    this.status = JobStatus.DONE;
    this.lastError = null;
  }

  /** Re-queues the job for a later attempt after a transient failure. */
  public void scheduleRetry(Instant nextRunAfter, String error) {
    this.status = JobStatus.PENDING;
    this.runAfter = nextRunAfter;
    this.lastError = error;
  }

  /** Gives up on the job after exhausting its retries. */
  public void markFailed(String error) {
    this.status = JobStatus.FAILED;
    this.lastError = error;
  }

  public UUID getId() {
    return id;
  }

  public String getType() {
    return type;
  }

  public String getPayload() {
    return payload;
  }

  public JobStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Instant getRunAfter() {
    return runAfter;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Job other)) {
      return false;
    }
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
