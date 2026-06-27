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
 * Normalizes a post-login redirect target to a safe, same-origin relative path,
 * falling back to the app root for anything else.
 *
 * Canonicalising with {@link URL} against the app origin is what makes this robust:
 * it collapses backslashes and protocol-relative forms (`//host`, `/\host`) and
 * surfaces the true target origin regardless of percent-encoding, so encoded open
 * redirects like `/%2F%2Fevil.example.com` (which a literal `//` check would miss)
 * are rejected too.
 */
export function safeRedirectPath(target: string | null | undefined, fallback = '/'): string {
  if (!target || !target.startsWith('/')) {
    return fallback;
  }
  let resolved: URL;
  try {
    resolved = new URL(target, window.location.origin);
  } catch {
    return fallback;
  }
  if (resolved.origin !== window.location.origin) {
    return fallback;
  }
  // Guard against a path that only becomes protocol-relative once decoded (e.g.
  // `/%2F%2Fevil` → `//evil`), which a downstream decode could turn off-origin.
  const decodedPath = safeDecode(resolved.pathname);
  if (decodedPath === null || decodedPath.startsWith('//') || decodedPath.startsWith('/\\')) {
    return fallback;
  }
  return `${resolved.pathname}${resolved.search}${resolved.hash}`;
}

function safeDecode(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}
