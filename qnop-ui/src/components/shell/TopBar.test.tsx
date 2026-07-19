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
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { TopBar } from './TopBar';

function renderTopBar() {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter>
        <TopBar isMobile={false} onToggleSidebar={vi.fn()} />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

describe('TopBar notifications placeholder (#514)', () => {
  it('opens the coming-soon panel on bell click and closes it again', async () => {
    const user = userEvent.setup();
    renderTopBar();

    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    expect(await screen.findByText(/coming soon/i)).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
  });

  it('shows no fake unread indicator on the bell', () => {
    renderTopBar();
    const bell = screen.getByRole('button', { name: 'Notifications' });
    expect(bell.querySelector('.MuiBadge-dot')).toBeNull();
  });
});
