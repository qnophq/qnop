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

import { create } from 'zustand';
import type { CurrentUserResponse, UserRole, UserSource } from '../api/generated';
import { csrfHeaders, performRefresh } from '../api/refresh';

const API_BASE = '/api/v1';

interface AuthState {
  /** Access token — kept in memory only, never persisted (ADR-aligned with Plugwerk). */
  accessToken: string | null;
  userId: string | null;
  displayName: string | null;
  email: string | null;
  role: UserRole | null;
  source: UserSource | null;
  /** URL of the current user's profile picture, or null for the initials avatar. */
  avatarUrl: string | null;
  isAuthenticated: boolean;
  /** True while the initial refresh-on-load is in flight; gates the first render. */
  isHydrating: boolean;
  /**
   * True when the backend has a valid session but forces a password change
   * before any non-auth resource (the PasswordChangeRequiredFilter returns 403
   * on /users/me). The token is kept so the user can reach /change-password.
   */
  passwordChangeRequired: boolean;

  setAccessToken: (token: string | null) => void;
  /** Updates just the avatar URL after a self-service upload/remove, so the shell reflects it. */
  setAvatarUrl: (url: string | null) => void;
  login: (usernameOrEmail: string, password: string) => Promise<void>;
  fetchMe: () => Promise<void>;
  hydrate: () => Promise<void>;
  logout: () => Promise<void>;
  clearAuth: () => void;
}

const EMPTY_PROFILE = {
  userId: null,
  displayName: null,
  email: null,
  role: null,
  source: null,
  avatarUrl: null,
} as const;

export const useAuthStore = create<AuthState>((set, get) => ({
  accessToken: null,
  ...EMPTY_PROFILE,
  isAuthenticated: false,
  isHydrating: true,
  passwordChangeRequired: false,

  setAccessToken: (token) => set({ accessToken: token }),

  setAvatarUrl: (url) => set({ avatarUrl: url }),

  login: async (usernameOrEmail, password) => {
    const response = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ usernameOrEmail, password }),
    });
    if (!response.ok) {
      throw new Error(response.status === 429 ? 'RATE_LIMITED' : 'INVALID_CREDENTIALS');
    }
    const body = (await response.json()) as { accessToken: string };
    set({ accessToken: body.accessToken });
    await get().fetchMe();
  },

  fetchMe: async () => {
    const token = get().accessToken;
    const response = await fetch(`${API_BASE}/users/me`, {
      credentials: 'include',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (response.ok) {
      const me = (await response.json()) as CurrentUserResponse;
      set({
        userId: me.id,
        displayName: me.displayName,
        email: me.email,
        role: me.role,
        source: me.source,
        avatarUrl: me.avatarUrl ?? null,
        isAuthenticated: true,
        passwordChangeRequired: false,
      });
      return;
    }
    // A 403 here means a valid session that must change its password first; keep
    // the token so /change-password is reachable. Any other failure clears auth.
    if (response.status === 403 && token) {
      set({ ...EMPTY_PROFILE, isAuthenticated: false, passwordChangeRequired: true });
      return;
    }
    get().clearAuth();
  },

  hydrate: async () => {
    try {
      const token = await performRefresh();
      if (!token) {
        get().clearAuth();
        return;
      }
      set({ accessToken: token });
      await get().fetchMe();
    } finally {
      set({ isHydrating: false });
    }
  },

  logout: async () => {
    const token = get().accessToken;
    try {
      await fetch(`${API_BASE}/auth/logout`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          ...csrfHeaders(),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      });
    } catch {
      // Even if the network call fails, clear local state below.
    }
    get().clearAuth();
  },

  clearAuth: () =>
    set({
      accessToken: null,
      ...EMPTY_PROFILE,
      isAuthenticated: false,
      passwordChangeRequired: false,
    }),
}));

/** Whether the current user has the global ADMIN role. */
export function selectIsAdmin(state: AuthState): boolean {
  return state.role === 'ADMIN';
}
