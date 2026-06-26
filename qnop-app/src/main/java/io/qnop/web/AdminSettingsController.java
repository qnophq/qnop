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

import io.qnop.api.v1.endpoint.AdminSettingsApi;
import io.qnop.api.v1.model.AdminSetting;
import io.qnop.api.v1.model.AdminSettingsResponse;
import io.qnop.api.v1.model.AdminSettingsUpdateRequest;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.FieldError;
import io.qnop.api.v1.model.SettingValueType;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.SettingsValidationException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * Superadmin settings administration ({@code GET/PATCH /api/v1/admin/settings}), implementing the
 * generated {@link AdminSettingsApi} contract (issue #16, ADR-0025). Authorization for the {@code
 * /api/v1/admin/**} namespace is enforced centrally in the security filter chain (issue #10).
 *
 * <p>Sensitive values are returned masked ({@code ***}); sending the mask back on {@code PATCH}
 * leaves the stored secret unchanged. The editing user is not yet attributed — the authenticated
 * principal is wired in once the auth subsystem lands (issue #17).
 */
@RestController
public class AdminSettingsController implements AdminSettingsApi {

  private final ApplicationSettingsService settings;

  public AdminSettingsController(ApplicationSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public ResponseEntity<AdminSettingsResponse> getAdminSettings() {
    return ResponseEntity.ok(currentSettings());
  }

  @Override
  public ResponseEntity<AdminSettingsResponse> updateAdminSettings(
      AdminSettingsUpdateRequest adminSettingsUpdateRequest) {
    settings.update(adminSettingsUpdateRequest.getValues(), null);
    return ResponseEntity.ok(currentSettings());
  }

  private AdminSettingsResponse currentSettings() {
    List<AdminSetting> items =
        settings.describeAll().stream()
            .map(
                descriptor ->
                    new AdminSetting()
                        .key(descriptor.key())
                        .value(descriptor.value())
                        .type(SettingValueType.fromValue(descriptor.type()))
                        .description(descriptor.description())
                        .sensitive(descriptor.sensitive())
                        .allowedValues(descriptor.allowedValues()))
            .toList();
    return new AdminSettingsResponse().settings(items);
  }

  @ExceptionHandler(SettingsValidationException.class)
  public ResponseEntity<ErrorResponse> onInvalidSettings(SettingsValidationException ex) {
    List<FieldError> fieldErrors =
        ex.getFieldErrors().stream()
            .map(error -> new FieldError().field(error.field()).message(error.message()))
            .toList();
    String message =
        fieldErrors.size() == 1
            ? "1 setting is invalid."
            : fieldErrors.size() + " settings are invalid.";
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse()
                .code("VALIDATION_ERROR")
                .message(message)
                .fieldErrors(fieldErrors)
                .timestamp(OffsetDateTime.now()));
  }
}
