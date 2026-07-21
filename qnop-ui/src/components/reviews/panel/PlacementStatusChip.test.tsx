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

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactNode } from 'react';
import { PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { PlacementStatusChip } from './PlacementStatusChip';

function wrapper({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>;
}

describe('PlacementStatusChip', () => {
  it('shows a spinner while re-anchoring is pending (#552)', () => {
    render(<PlacementStatusChip status={PlacementStatus.Pending} />, { wrapper });

    expect(screen.getByText('Re-anchoring…')).toBeTruthy();
    expect(screen.getByTestId('placement-pending-spinner')).toBeTruthy();
  });

  it.each([PlacementStatus.Moved, PlacementStatus.Orphaned, PlacementStatus.Failed] as const)(
    'renders %s without a spinner',
    (status) => {
      render(<PlacementStatusChip status={status} />, { wrapper });

      expect(screen.queryByTestId('placement-pending-spinner')).toBeNull();
    },
  );

  it('renders nothing for PLACED — the expected state needs no badge', () => {
    const { container } = render(<PlacementStatusChip status={PlacementStatus.Placed} />, {
      wrapper,
    });

    expect(container).toBeEmptyDOMElement();
  });
});
