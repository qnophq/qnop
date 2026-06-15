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

import io.qnop.api.v1.endpoint.AuthPasswordResetApi;
import io.qnop.api.v1.model.ForgotPasswordRequest;
import io.qnop.api.v1.model.ResetPasswordRequest;
import io.qnop.service.auth.PasswordResetFlowService;
import io.qnop.service.auth.PasswordResetTokenService.InvalidPasswordResetTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-service password-reset endpoints (issue #20), implementing the generated {@link
 * AuthPasswordResetApi}. Public; the {@code User} entity never reaches this layer — the flow lives
 * in {@link PasswordResetFlowService} (ADR-0004).
 *
 * <p><strong>Anti-enumeration:</strong> {@code forgot-password} always returns {@code 204},
 * disclosing nothing about whether the address is registered.
 */
@RestController
public class AuthPasswordResetController implements AuthPasswordResetApi {

  private final PasswordResetFlowService passwordResetFlow;

  public AuthPasswordResetController(PasswordResetFlowService passwordResetFlow) {
    this.passwordResetFlow = passwordResetFlow;
  }

  @Override
  public ResponseEntity<Void> forgotPassword(ForgotPasswordRequest request) {
    passwordResetFlow.requestReset(request.getEmail());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> resetPassword(ResetPasswordRequest request) {
    try {
      passwordResetFlow.reset(request.getToken(), request.getNewPassword());
    } catch (InvalidPasswordResetTokenException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    return ResponseEntity.noContent().build();
  }
}
