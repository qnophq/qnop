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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { csrfHeaders, performRefresh } from './refresh';

function setCookie(value: string) {
  Object.defineProperty(document, 'cookie', { value, writable: true, configurable: true });
}

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as Response;
}

describe('refresh', () => {
  beforeEach(() => {
    setCookie('');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('csrfHeaders reads the XSRF-TOKEN cookie', () => {
    setCookie('XSRF-TOKEN=abc123');
    expect(csrfHeaders()).toEqual({ 'X-XSRF-TOKEN': 'abc123' });
  });

  it('csrfHeaders is empty when no token cookie is present', () => {
    expect(csrfHeaders()).toEqual({});
  });

  it('primes the CSRF cookie then refreshes, sending the token header', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/config')) {
        setCookie('XSRF-TOKEN=primed');
        return Promise.resolve(jsonResponse({}));
      }
      return Promise.resolve(jsonResponse({ accessToken: 'fresh' }));
    });
    vi.stubGlobal('fetch', fetchMock);

    const token = await performRefresh();

    expect(token).toBe('fresh');
    const refreshCall = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/auth/refresh'));
    expect(refreshCall?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': 'primed' },
    });
  });

  it('returns null when the refresh request fails', async () => {
    setCookie('XSRF-TOKEN=t');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, false)));

    expect(await performRefresh()).toBeNull();
  });

  it('coalesces concurrent calls into a single in-flight request', async () => {
    setCookie('XSRF-TOKEN=t');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ accessToken: 'one' }));
    vi.stubGlobal('fetch', fetchMock);

    const [a, b] = await Promise.all([performRefresh(), performRefresh()]);

    expect(a).toBe('one');
    expect(b).toBe('one');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
