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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { userSettingsApi } from '../../api/config';
import { DEVICE_LOCALE_STORAGE_KEY } from '../../i18n/locales';
import { useUiStore } from '../../stores/uiStore';
import { AccountLanguageSwitcher, DeviceLanguageSwitcher } from './LanguageSwitcher';

vi.mock('../../api/config', () => ({
  userSettingsApi: { updateCurrentUserSettings: vi.fn(), getCurrentUserSettings: vi.fn() },
}));

function openMenuAndPick(name: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Change language' }));
  fireEvent.click(screen.getByRole('menuitem', { name }));
}

describe('LanguageSwitcher', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useUiStore.setState({ locale: 'en', deviceLocale: undefined });
  });
  afterEach(() => localStorage.clear());

  it('lists every language by its own name and marks the active one', () => {
    render(<DeviceLanguageSwitcher />);
    fireEvent.click(screen.getByRole('button', { name: 'Change language' }));
    expect(screen.getByRole('menuitem', { name: 'Deutsch' })).toHaveAttribute('lang', 'de');
    expect(screen.getByRole('menuitem', { name: 'English' })).toHaveClass('Mui-selected');
  });

  it('keeps a pre-login choice on this device only', () => {
    render(<DeviceLanguageSwitcher />);
    openMenuAndPick('Deutsch');
    expect(useUiStore.getState().deviceLocale).toBe('de');
    expect(localStorage.getItem(DEVICE_LOCALE_STORAGE_KEY)).toBe('de');
  });

  it('saves a signed-in choice to the preferred_language setting', async () => {
    vi.mocked(userSettingsApi.updateCurrentUserSettings).mockResolvedValue({
      data: { settings: [{ key: 'preferred_language', value: 'de', type: 'STRING' }] },
    } as Awaited<ReturnType<typeof userSettingsApi.updateCurrentUserSettings>>);
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <AccountLanguageSwitcher />
      </QueryClientProvider>,
    );

    openMenuAndPick('Deutsch');

    await waitFor(() =>
      expect(userSettingsApi.updateCurrentUserSettings).toHaveBeenCalledWith({
        userSettingsUpdateRequest: { values: { preferred_language: 'de' } },
      }),
    );
    expect(localStorage.getItem(DEVICE_LOCALE_STORAGE_KEY)).toBeNull();
  });

  it('tells the user when the choice could not be saved', async () => {
    vi.mocked(userSettingsApi.updateCurrentUserSettings).mockRejectedValue(new Error('500'));
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <AccountLanguageSwitcher />
      </QueryClientProvider>,
    );

    openMenuAndPick('Deutsch');

    expect(await screen.findByText('Your language choice could not be saved.')).toBeVisible();
  });
});
