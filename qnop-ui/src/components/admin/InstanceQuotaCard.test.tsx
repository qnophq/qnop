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
import type { InstanceLimitsResponse } from '../../api/generated';
import { useInstanceLimits } from '../../api/hooks/useAdminConfiguration';
import { renderWithProviders } from '../../test/renderWithProviders';
import { InstanceQuotaCard } from './InstanceQuotaCard';

vi.mock('../../api/hooks/useAdminConfiguration', () => ({
  useInstanceLimits: vi.fn(),
}));

const limits = (overrides: Partial<InstanceLimitsResponse> = {}): InstanceLimitsResponse => ({
  users: { used: 18, maximum: 25 },
  teams: { used: 3, maximum: 5 },
  teamMembers: { used: 9, maximum: 10 },
  activeReviews: { used: 42, maximum: 0 },
  ...overrides,
});

const mockLimits = (data: InstanceLimitsResponse | undefined) =>
  vi.mocked(useInstanceLimits).mockReturnValue({ data } as ReturnType<typeof useInstanceLimits>);

describe('InstanceQuotaCard', () => {
  it('shows usage against each quota', () => {
    mockLimits(limits());

    renderWithProviders(<InstanceQuotaCard />);

    expect(screen.getByText('18 of 25')).toBeInTheDocument();
    expect(screen.getByText('3 of 5')).toBeInTheDocument();
  });

  it('says when a quota has no ceiling instead of showing a bar to nowhere', () => {
    mockLimits(limits());

    renderWithProviders(<InstanceQuotaCard />);

    expect(screen.getByText('42 — no limit')).toBeInTheDocument();
    // Three bars, not four: the unlimited row has nothing to fill.
    expect(screen.getAllByRole('progressbar')).toHaveLength(3);
  });

  it('renders nothing at all where no quota is configured', () => {
    // Every Community deployment. A card full of zeroes would be something to
    // interpret rather than something to read.
    mockLimits(
      limits({
        users: { used: 18, maximum: 0 },
        teams: { used: 3, maximum: 0 },
        teamMembers: { used: 9, maximum: 0 },
      }),
    );

    const { container } = renderWithProviders(<InstanceQuotaCard />);

    expect(container).toBeEmptyDOMElement();
  });

  it('survives an instance sitting above its quota', () => {
    // The state after somebody lowers a limit: existing records stay, so usage
    // legitimately exceeds the ceiling and must still render.
    mockLimits(limits({ users: { used: 40, maximum: 25 } }));

    renderWithProviders(<InstanceQuotaCard />);

    expect(screen.getByText('40 of 25')).toBeInTheDocument();
    const bar = screen.getByLabelText('User accounts: 40 of 25');
    expect(bar).toHaveAttribute('aria-valuenow', '100');
  });

  it('renders nothing before the data arrives', () => {
    mockLimits(undefined);

    const { container } = renderWithProviders(<InstanceQuotaCard />);

    expect(container).toBeEmptyDOMElement();
  });
});
