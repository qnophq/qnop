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

const LABELS = ['', 'Weak', 'Fair', 'Good', 'Strong'] as const;

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

// Readable character classes for self-service generation — ambiguous glyphs
// (0/O, 1/l/I) are dropped so a copied password is hard to mistype.
const LOWER = 'abcdefghijkmnpqrstuvwxyz';
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const DIGITS = '23456789';
const SYMBOLS = '!@#$%^&*-_=+';
const ALL = LOWER + UPPER + DIGITS + SYMBOLS;

/** Default length for a generated password — comfortably above the 8-char policy. */
export const GENERATED_PASSWORD_LENGTH = 20;

/**
 * Returns an unbiased index in {@code [0, maxExclusive)} using the Web Crypto API
 * with rejection sampling (no {@code Math.random}, no modulo bias).
 */
function randomIndex(maxExclusive: number): number {
  const limit = Math.floor(0x1_0000_0000 / maxExclusive) * maxExclusive;
  const buffer = new Uint32Array(1);
  let value: number;
  do {
    crypto.getRandomValues(buffer);
    value = buffer[0];
  } while (value >= limit);
  return value % maxExclusive;
}

const pick = (chars: string): string => chars[randomIndex(chars.length)];

/**
 * Generates a strong random password for the self-service change screen (#116).
 * Guarantees at least one lowercase, uppercase, digit and symbol — so it always
 * clears the policy and reads as "Strong" — then fills and shuffles the rest with
 * cryptographically secure randomness.
 */
export function generateStrongPassword(length: number = GENERATED_PASSWORD_LENGTH): string {
  const required = [pick(LOWER), pick(UPPER), pick(DIGITS), pick(SYMBOLS)];
  const rest = Array.from({ length: Math.max(0, length - required.length) }, () => pick(ALL));
  const chars = [...required, ...rest];
  // Fisher–Yates shuffle so the guaranteed characters are not always up front.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
