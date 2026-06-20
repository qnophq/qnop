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
 * Refreshes the access token using the HttpOnly `qnop_refresh` cookie. This is a
 * pure, fetch-based function with no store/axios dependency — both the auth
 * store (on hydration) and the axios 401 interceptor call it, and each updates
 * its own state with the result. Keeping it dependency-free avoids an import
 * cycle (axios → store → axios).
 *
 * The refresh endpoint is CSRF-protected (double-submit cookie). The CSRF
 * cookie is emitted by the backend on any prior response; if it is missing we
 * prime it with a public GET first. Concurrent callers share one in-flight
 * request (single-flight).
 */
const API_BASE = '/api/v1';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

let inFlight: Promise<string | null> | null = null;

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * CSRF double-submit header for state-changing cookie endpoints (refresh,
 * logout). Returns an empty object when no token is present.
 */
export function csrfHeaders(): Record<string, string> {
  const token = readCookie(CSRF_COOKIE);
  return token ? { [CSRF_HEADER]: token } : {};
}

/** Ensures a CSRF cookie exists by priming with a public GET when absent. */
async function ensureCsrfToken(): Promise<string | null> {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) {
    return existing;
  }
  try {
    await fetch(`${API_BASE}/config`, { credentials: 'include' });
  } catch {
    // Priming is best-effort; the refresh below will simply fail if there is
    // genuinely no connectivity.
  }
  return readCookie(CSRF_COOKIE);
}

async function postRefresh(): Promise<string | null> {
  const csrf = await ensureCsrfToken();
  const headers: Record<string, string> = {};
  if (csrf) {
    headers[CSRF_HEADER] = csrf;
  }
  const response = await fetch(`${API_BASE}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers,
  });
  if (!response.ok) {
    return null;
  }
  const body = (await response.json()) as { accessToken?: string };
  return body.accessToken ?? null;
}

/** Returns a fresh access token, or null when the session cannot be refreshed. */
export function performRefresh(): Promise<string | null> {
  if (!inFlight) {
    inFlight = postRefresh().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}
