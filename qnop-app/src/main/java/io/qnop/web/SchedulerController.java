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
package io.qnop.web;

import io.qnop.api.v1.endpoint.AdminSchedulerApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.SchedulerJob;
import io.qnop.api.v1.model.SchedulerJobListResponse;
import io.qnop.api.v1.model.SchedulerJobUpdateRequest;
import io.qnop.service.scheduler.DryRunNotSupportedException;
import io.qnop.service.scheduler.SchedulerJobBusyException;
import io.qnop.service.scheduler.SchedulerJobNotFoundException;
import io.qnop.service.scheduler.SchedulerService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin maintenance-scheduler dashboard ({@code /api/v1/admin/scheduler}), implementing the
 * generated {@link AdminSchedulerApi} contract (issue #524, ADR-0045). Authorization is enforced
 * centrally by the security chain ({@code /api/v1/admin/**} requires {@code ADMIN}); a {@code
 * MEMBER} never reaches these methods (403). A thin mapping over {@link SchedulerService}, which
 * owns the gate, the transactions and the SYSTEM audit writes.
 */
@RestController
public class SchedulerController implements AdminSchedulerApi {

  private final SchedulerService scheduler;

  public SchedulerController(SchedulerService scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public ResponseEntity<SchedulerJobListResponse> listSchedulerJobs() {
    return ResponseEntity.ok(
        new SchedulerJobListResponse()
            .items(scheduler.list().stream().map(SchedulerController::toDto).toList()));
  }

  @Override
  public ResponseEntity<SchedulerJob> updateSchedulerJob(
      String jobId, SchedulerJobUpdateRequest request) {
    return ResponseEntity.ok(
        toDto(
            scheduler.updateSettings(
                CurrentUser.requireUserId(), jobId, request.getEnabled(), request.getDryRun())));
  }

  @Override
  public ResponseEntity<SchedulerJob> runSchedulerJob(String jobId) {
    return ResponseEntity.ok(toDto(scheduler.runNow(CurrentUser.requireUserId(), jobId)));
  }

  @ExceptionHandler(SchedulerJobNotFoundException.class)
  public ResponseEntity<ErrorResponse> onNotFound(SchedulerJobNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "SCHEDULER_JOB_NOT_FOUND", ex.getMessage());
  }

  @ExceptionHandler(DryRunNotSupportedException.class)
  public ResponseEntity<ErrorResponse> onDryRunUnsupported(DryRunNotSupportedException ex) {
    return error(HttpStatus.BAD_REQUEST, "DRY_RUN_NOT_SUPPORTED", ex.getMessage());
  }

  @ExceptionHandler(SchedulerJobBusyException.class)
  public ResponseEntity<ErrorResponse> onBusy(SchedulerJobBusyException ex) {
    return error(HttpStatus.CONFLICT, "SCHEDULER_JOB_BUSY", ex.getMessage());
  }

  private static SchedulerJob toDto(SchedulerService.SchedulerJobView view) {
    return new SchedulerJob()
        .jobId(view.jobId())
        .displayName(view.displayName())
        .description(view.description())
        .cron(view.cron())
        .supportsDryRun(view.supportsDryRun())
        .enabled(view.enabled())
        .dryRun(view.dryRun())
        .lastRunAt(toOffset(view.lastRunAt()))
        .lastOutcome(view.lastOutcome())
        .lastTrigger(view.lastTrigger())
        .lastDetail(view.lastDetail());
  }

  private static OffsetDateTime toOffset(java.time.Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse().code(code).message(message).timestamp(OffsetDateTime.now()));
  }
}
