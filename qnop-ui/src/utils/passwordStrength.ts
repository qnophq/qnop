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

export interface PasswordStrength {
  /** 0 (empty) … 4 (strong). */
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
  /** Whether the password is acceptable for submission (length ≥ 8). */
  acceptable: boolean;
}

const LABELS = ['', 'Schwach', 'Mittel', 'Gut', 'Stark'] as const;

/**
 * A lightweight password-strength estimate for the register/reset meters
 * (mirrors the design prototype). Pure and synchronous — not a security control;
 * the backend enforces the real password policy.
 */
export function passwordStrength(password: string): PasswordStrength {
  let raw = 0;
  if (password.length >= 8) raw++;
  if (/[A-Z]/.test(password) && /[a-z]/.test(password)) raw++;
  if (/[0-9]/.test(password)) raw++;
  if (/[^A-Za-z0-9]/.test(password)) raw++;
  const score = (password.length === 0 ? 0 : raw) as PasswordStrength['score'];
  return { score, label: LABELS[score], acceptable: password.length >= 8 };
}
