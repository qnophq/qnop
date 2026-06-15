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

import io.qnop.api.v1.endpoint.AuthRegistrationApi;
import io.qnop.api.v1.model.RegisterRequest;
import io.qnop.api.v1.model.RegisterResponse;
import io.qnop.api.v1.model.VerifyEmailResponse;
import io.qnop.service.ApplicationSettingKey;
import io.qnop.service.ApplicationSettingsService;
import io.qnop.service.auth.EmailVerificationTokenService.InvalidVerificationTokenException;
import io.qnop.service.auth.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-registration and email-verification endpoints (issue #20), implementing the generated {@link
 * AuthRegistrationApi}. Public; the {@code User} entity never reaches this layer — all of it lives
 * in {@link RegistrationService} (ADR-0004).
 *
 * <p><strong>Anti-enumeration:</strong> {@code register} returns a uniform acknowledgement whether
 * or not the account already exists, and a disguised {@code 404} when self-registration is
 * disabled.
 */
@RestController
public class AuthRegistrationController implements AuthRegistrationApi {

  private final RegistrationService registrationService;
  private final ApplicationSettingsService settings;

  public AuthRegistrationController(
      RegistrationService registrationService, ApplicationSettingsService settings) {
    this.registrationService = registrationService;
    this.settings = settings;
  }

  @Override
  public ResponseEntity<RegisterResponse> register(RegisterRequest request) {
    if (!settings.getBoolean(ApplicationSettingKey.AUTH_SELF_REGISTRATION_ENABLED)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
    registrationService.register(
        request.getUsername(), request.getEmail(), request.getPassword(), request.getDisplayName());
    return ResponseEntity.ok(
        new RegisterResponse()
            .verificationRequired(true)
            .message("If the details are valid, a verification email has been sent."));
  }

  @Override
  public ResponseEntity<VerifyEmailResponse> verifyEmail(String token) {
    try {
      registrationService.verify(token);
    } catch (InvalidVerificationTokenException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.ok(
        new VerifyEmailResponse().verified(true).message("Email verified. You can now sign in."));
  }
}
