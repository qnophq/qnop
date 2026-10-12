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

import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import i18n from '../i18n';
import { serverConfigApi, userSettingsApi } from '../api/config';
import { useUiStore } from '../stores/uiStore';
import { LanguageSync } from './LanguageSync';

vi.mock('../api/config', () => ({
  serverConfigApi: { getServerConfig: vi.fn() },
  userSettingsApi: { getCurrentUserSettings: vi.fn() },
}));

vi.mock('../stores/authStore', () => ({
  useAuthStore: (selector: (s: { isAuthenticated: boolean }) => unknown) =>
    selector({ isAuthenticated: true }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function mockConfig(defaultLanguage: string) {
  vi.mocked(serverConfigApi.getServerConfig).mockResolvedValue({
    data: {
      edition: 'COMMUNITY',
      general: { siteName: 'qnop', defaultTimezone: 'UTC', defaultLanguage },
    },
  } as Awaited<ReturnType<typeof serverConfigApi.getServerConfig>>);
}

function mockPreferredLanguage(value: string) {
  vi.mocked(userSettingsApi.getCurrentUserSettings).mockResolvedValue({
    data: { settings: [{ key: 'preferred_language', value, type: 'STRING' }] },
  } as Awaited<ReturnType<typeof userSettingsApi.getCurrentUserSettings>>);
}

describe('LanguageSync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useUiStore.setState({ locale: 'en', deviceLocale: undefined });
  });
  afterEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('restores the stored preference: switches i18next, <html lang> and the store', async () => {
    mockConfig('en');
    mockPreferredLanguage('de');

    render(<LanguageSync />, { wrapper });

    await waitFor(() => expect(useUiStore.getState().locale).toBe('de'));
    expect(i18n.language).toBe('de');
    expect(document.documentElement.lang).toBe('de');
    expect(i18n.t('language.label')).toBe('Sprache');
  });

  it('follows the application default when the user has no preference', async () => {
    mockConfig('de');
    mockPreferredLanguage('');

    render(<LanguageSync />, { wrapper });

    await waitFor(() => expect(useUiStore.getState().locale).toBe('de'));
  });

  it('lets a stored preference outrank the device choice', async () => {
    mockConfig('de');
    mockPreferredLanguage('en');
    useUiStore.setState({ deviceLocale: 'de' });

    render(<LanguageSync />, { wrapper });

    await waitFor(() => expect(serverConfigApi.getServerConfig).toHaveBeenCalled());
    await waitFor(() => expect(document.documentElement.lang).toBe('en'));
    expect(useUiStore.getState().locale).toBe('en');
  });
});
