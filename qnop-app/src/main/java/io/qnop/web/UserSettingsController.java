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

import io.qnop.api.v1.endpoint.UserSettingsApi;
import io.qnop.api.v1.model.ErrorResponse;
import io.qnop.api.v1.model.SettingValueType;
import io.qnop.api.v1.model.UserSettingItem;
import io.qnop.api.v1.model.UserSettingsResponse;
import io.qnop.api.v1.model.UserSettingsUpdateRequest;
import io.qnop.service.SettingValidationException;
import io.qnop.service.UserSettingsService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated user's own settings ({@code GET/PATCH /api/v1/users/me/settings}), implementing
 * the generated {@link UserSettingsApi} contract (issue #22). The acting user is the JWT subject
 * ({@link CurrentUser}); non-user (API-key) principals are rejected with 403.
 */
@RestController
public class UserSettingsController implements UserSettingsApi {

  private final UserSettingsService settings;

  public UserSettingsController(UserSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public ResponseEntity<UserSettingsResponse> getCurrentUserSettings() {
    return ResponseEntity.ok(toResponse(settings.getSettings(CurrentUser.requireUserId())));
  }

  @Override
  public ResponseEntity<UserSettingsResponse> updateCurrentUserSettings(
      UserSettingsUpdateRequest userSettingsUpdateRequest) {
    return ResponseEntity.ok(
        toResponse(
            settings.updateSettings(
                CurrentUser.requireUserId(), userSettingsUpdateRequest.getValues())));
  }

  private UserSettingsResponse toResponse(List<UserSettingsService.SettingView> views) {
    List<UserSettingItem> items =
        views.stream()
            .map(
                view ->
                    new UserSettingItem()
                        .key(view.key())
                        .value(view.value())
                        .type(SettingValueType.fromValue(view.type()))
                        .description(view.description()))
            .toList();
    return new UserSettingsResponse().settings(items);
  }

  @ExceptionHandler(SettingValidationException.class)
  public ResponseEntity<ErrorResponse> onInvalidSetting(SettingValidationException ex) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse()
                .code("INVALID_SETTING")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now()));
  }
}
