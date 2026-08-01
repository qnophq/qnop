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

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InfoBanner as InfoBannerModel } from '../../api/generated';
import { renderWithProviders } from '../../test/renderWithProviders';
import { InfoBanner } from './InfoBanner';

const banner = (overrides: Partial<InfoBannerModel> = {}): InfoBannerModel =>
  ({
    severity: 'info',
    message: 'Demo installation — sign in with demo@qnop.io / demo',
    ...overrides,
  }) as InfoBannerModel;

describe('InfoBanner', () => {
  it('renders the operator message', () => {
    renderWithProviders(<InfoBanner banner={banner()} />);

    expect(screen.getByText(/Demo installation/)).toBeInTheDocument();
  });

  it('announces politely, and assertively when critical', () => {
    const { unmount } = renderWithProviders(
      <InfoBanner banner={banner({ severity: 'warning' })} />,
    );
    expect(screen.getByRole('status')).toBeInTheDocument();
    unmount();

    // A critical notice interrupts a screen reader; the other two wait their turn.
    renderWithProviders(<InfoBanner banner={banner({ severity: 'critical' })} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('renders the link with a severed opener, and only when both parts are set', () => {
    const { unmount } = renderWithProviders(
      <InfoBanner
        banner={banner({ linkLabel: 'Status page', linkUrl: 'https://status.example' })}
      />,
    );

    const link = screen.getByRole('link', { name: /Status page/ });
    expect(link).toHaveAttribute('href', 'https://status.example');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(link).toHaveAttribute('target', '_blank');
    unmount();

    renderWithProviders(<InfoBanner banner={banner({ linkLabel: 'Status page' })} />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('offers dismissal only where the caller handles it', async () => {
    const onDismiss = vi.fn();
    const { unmount } = renderWithProviders(<InfoBanner banner={banner()} onDismiss={onDismiss} />);

    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(onDismiss).toHaveBeenCalledOnce();
    unmount();

    // The sign-in banner answers a question the visitor is about to ask, so it
    // has no close button at all.
    renderWithProviders(<InfoBanner banner={banner()} variant="card" />);
    expect(screen.queryByRole('button', { name: /dismiss/i })).not.toBeInTheDocument();
  });

  it('falls back to the info tone for a severity it does not know', () => {
    renderWithProviders(
      <InfoBanner
        banner={{ severity: 'nonsense', message: 'Still readable' } as InfoBannerModel}
      />,
    );

    // A future severity must never blank the notice out.
    expect(screen.getByText('Still readable')).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });
});
