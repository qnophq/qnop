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

import io.qnop.api.v1.endpoint.AdminStorageConsistencyApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.MissingBinary;
import io.qnop.api.v1.model.OrphanDeleteRequest;
import io.qnop.api.v1.model.OrphanDeleteResponse;
import io.qnop.api.v1.model.OrphanedObject;
import io.qnop.api.v1.model.SkippedOrphan;
import io.qnop.api.v1.model.StorageConsistencyReport;
import io.qnop.api.v1.model.StorageConsistencySummary;
import io.qnop.service.storage.StorageConsistencyService;
import io.qnop.service.storage.StorageConsistencyService.ConsistencyReport;
import io.qnop.service.storage.StorageConsistencyService.MissingBinaryView;
import io.qnop.service.storage.StorageConsistencyService.OrphanView;
import io.qnop.service.storage.StorageRemediationService;
import io.qnop.service.storage.StorageScanLimitExceededException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only storage-consistency dashboard endpoints (issue #523, ADR-0044), implementing the
 * generated {@link AdminStorageConsistencyApi} — a thin mapping over the scan and remediation
 * services. Authorization is enforced centrally by the security chain ({@code /api/v1/admin/**}
 * requires {@code ADMIN}). The scan circuit-breaker surfaces as {@code 409}.
 */
@RestController
public class AdminStorageConsistencyController implements AdminStorageConsistencyApi {

  private final StorageConsistencyService scanner;
  private final StorageRemediationService remediation;

  public AdminStorageConsistencyController(
      StorageConsistencyService scanner, StorageRemediationService remediation) {
    this.scanner = scanner;
    this.remediation = remediation;
  }

  @Override
  public ResponseEntity<StorageConsistencyReport> scanStorageConsistency() {
    return ResponseEntity.ok(toDto(scanner.scan()));
  }

  @Override
  public ResponseEntity<OrphanDeleteResponse> deleteOrphanedObjects(OrphanDeleteRequest request) {
    StorageRemediationService.RemediationResult result =
        remediation.deleteOrphans(request.getKeys(), CurrentUser.requireUserId());
    return ResponseEntity.ok(
        new OrphanDeleteResponse()
            .deleted(result.deleted())
            .skipped(
                result.skipped().stream()
                    .map(s -> new SkippedOrphan().storageKey(s.storageKey()).reason(s.reason()))
                    .toList()));
  }

  @ExceptionHandler(StorageScanLimitExceededException.class)
  public ResponseEntity<ErrorResponse> onScanLimit(StorageScanLimitExceededException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ErrorResponse()
                .code(ex.getCode())
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private static StorageConsistencyReport toDto(ConsistencyReport report) {
    return new StorageConsistencyReport()
        .summary(
            new StorageConsistencySummary()
                .dbReferencedCount(report.summary().dbReferencedCount())
                .storageObjectCount(report.summary().storageObjectCount())
                .missingCount(report.summary().missingCount())
                .orphanedCount(report.summary().orphanedCount())
                .scannedAt(report.summary().scannedAt().atOffset(ZoneOffset.UTC)))
        .missing(
            report.missing().stream().map(AdminStorageConsistencyController::toMissingDto).toList())
        .orphaned(
            report.orphaned().stream()
                .map(AdminStorageConsistencyController::toOrphanDto)
                .toList());
  }

  private static MissingBinary toMissingDto(MissingBinaryView view) {
    return new MissingBinary()
        .storageKey(view.storageKey())
        .kind(MissingBinary.KindEnum.fromValue(view.kind().name()))
        .documentId(view.documentId())
        .documentTitle(view.documentTitle())
        .documentSlug(view.documentSlug())
        .versionNumber(view.versionNumber())
        .attachmentName(view.attachmentName());
  }

  private static OrphanedObject toOrphanDto(OrphanView view) {
    return new OrphanedObject()
        .storageKey(view.storageKey())
        .size(view.size())
        .lastModified(view.lastModified().atOffset(ZoneOffset.UTC));
  }
}
