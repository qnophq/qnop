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
import { selectIsAdmin, useAuthStore } from './authStore';
import { performRefresh } from '../api/refresh';

vi.mock('../api/refresh', () => ({
  performRefresh: vi.fn(),
  csrfHeaders: () => ({}),
}));

const mockedRefresh = vi.mocked(performRefresh);

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as Response;
}

const ME = {
  id: 'u-1',
  displayName: 'Martin Kraus',
  email: 'martin@example.com',
  role: 'ADMIN',
  source: 'INTERNAL',
};

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth();
    useAuthStore.setState({ isHydrating: true });
    vi.restoreAllMocks();
    mockedRefresh.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('login stores the token and the profile from /users/me', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'tok-1' }))
      .mockResolvedValueOnce(jsonResponse(ME));
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().login('martin', 'pw');

    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('tok-1');
    expect(state.displayName).toBe('Martin Kraus');
    expect(state.role).toBe('ADMIN');
    expect(state.isAuthenticated).toBe(true);
    expect(selectIsAdmin(state)).toBe(true);
  });

  it('login throws on invalid credentials', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, false)));
    await expect(useAuthStore.getState().login('x', 'y')).rejects.toThrow();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('fetchMe clears auth on a 401', async () => {
    useAuthStore.setState({ accessToken: 'tok' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 } as Response));

    await useAuthStore.getState().fetchMe();

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('fetchMe flags a forced password change on a 403 and keeps the token', async () => {
    useAuthStore.setState({ accessToken: 'tok' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 } as Response));

    await useAuthStore.getState().fetchMe();

    const state = useAuthStore.getState();
    expect(state.passwordChangeRequired).toBe(true);
    expect(state.isAuthenticated).toBe(false);
    expect(state.accessToken).toBe('tok');
  });

  it('login throws RATE_LIMITED on a 429', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 429 } as Response));
    await expect(useAuthStore.getState().login('x', 'y')).rejects.toThrow('RATE_LIMITED');
  });

  it('hydrate authenticates when refresh yields a token', async () => {
    mockedRefresh.mockResolvedValue('tok-2');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(ME)));

    await useAuthStore.getState().hydrate();

    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.isHydrating).toBe(false);
  });

  it('hydrate clears auth when refresh fails', async () => {
    mockedRefresh.mockResolvedValue(null);

    await useAuthStore.getState().hydrate();

    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.isHydrating).toBe(false);
  });

  it('logout calls the endpoint and clears auth', async () => {
    useAuthStore.setState({ accessToken: 'tok', isAuthenticated: true });
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}, true));
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().logout();

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/auth/logout'),
      expect.objectContaining({ method: 'POST' }),
    );
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
