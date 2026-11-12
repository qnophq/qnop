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

/**
 * The SPA's Content-Security-Policy as `vite preview` serves it to the
 * Playwright suite (issue #913). The backend's `PathAwareCspHeaderWriter` is the
 * source of truth; `spaCspPolicy.test.ts` fails when the two drift apart.
 */
export function spaCspPolicy(nonce: string): string {
  return (
    `default-src 'self'; script-src 'self'; style-src 'self' 'nonce-${nonce}'; ` +
    "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
    "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; " +
    "form-action 'self'; object-src 'none'"
  );
}
