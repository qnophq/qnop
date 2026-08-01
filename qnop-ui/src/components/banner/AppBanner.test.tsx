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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitForElementToBeRemoved } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BannerResponse } from '../../api/generated';
import { useBanner } from '../../api/hooks/useBanner';
import { useAuthStore } from '../../stores/authStore';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AppBanner } from './AppBanner';

vi.mock('../../api/hooks/useBanner', () => ({
  useBanner: vi.fn(),
}));

const mockBanner = (data: BannerResponse | undefined) =>
  vi.mocked(useBanner).mockReturnValue({ data } as ReturnType<typeof useBanner>);

const MAINTENANCE: BannerResponse = {
  banner: { severity: 'warning', message: 'Maintenance on Saturday 20:00 UTC.' },
};

describe('AppBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ isAuthenticated: true });
  });

  it('shows the operator notice', () => {
    mockBanner(MAINTENANCE);

    renderWithProviders(<AppBanner />);

    expect(screen.getByText('Maintenance on Saturday 20:00 UTC.')).toBeInTheDocument();
  });

  it('renders nothing when there is no banner', () => {
    mockBanner({});

    renderWithProviders(<AppBanner />);

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('stays dismissed for that message, and returns when it changes', async () => {
    mockBanner(MAINTENANCE);
    const { unmount } = renderWithProviders(<AppBanner />);

    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    await waitForElementToBeRemoved(() => screen.queryByText(/Maintenance on Saturday/));
    unmount();

    // A reload does not bring it back …
    mockBanner(MAINTENANCE);
    const second = renderWithProviders(<AppBanner />);
    expect(screen.queryByText(/Maintenance on Saturday/)).not.toBeInTheDocument();
    second.unmount();

    // … but an edited notice does: it is something the user has not read.
    mockBanner({ banner: { severity: 'warning', message: 'Maintenance moved to Sunday 09:00.' } });
    renderWithProviders(<AppBanner />);
    expect(screen.getByText('Maintenance moved to Sunday 09:00.')).toBeInTheDocument();
  });

  it('does not ask the server while signed out', () => {
    useAuthStore.setState({ isAuthenticated: false });
    mockBanner(undefined);

    renderWithProviders(<AppBanner />);

    // The endpoint is authenticated; querying it from a signed-out shell would
    // only produce 401s in the console.
    expect(vi.mocked(useBanner)).toHaveBeenCalledWith(false);
  });
});
