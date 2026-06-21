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

import { authApi, authPasswordResetApi, authRegistrationApi } from './config';

/** Self-registration. Anti-enumeration: the backend always returns a uniform ack. */
export async function register(input: {
  username: string;
  email: string;
  password: string;
  displayName: string;
}): Promise<void> {
  await authRegistrationApi.register({ registerRequest: input });
}

/** Activates an account from an email-verification token. */
export async function verifyEmail(token: string): Promise<void> {
  await authRegistrationApi.verifyEmail({ token });
}

/** Requests a password-reset email. Uniform ack regardless of whether the account exists. */
export async function forgotPassword(email: string): Promise<void> {
  await authPasswordResetApi.forgotPassword({ forgotPasswordRequest: { email } });
}

/** Completes a password reset with the emailed token. */
export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await authPasswordResetApi.resetPassword({ resetPasswordRequest: { token, newPassword } });
}

/** Changes the authenticated user's password (invalidates existing tokens). */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await authApi.changePassword({ changePasswordRequest: { currentPassword, newPassword } });
}
