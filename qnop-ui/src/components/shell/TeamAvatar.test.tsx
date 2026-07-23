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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { TeamAvatar } from './TeamAvatar';

function renderAvatar(name: string | null, imageUrl?: string | null) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <TeamAvatar name={name} imageUrl={imageUrl} size={44} />
    </ThemeProvider>,
  );
}

describe('TeamAvatar', () => {
  it('shows the initials crest when no image is set', () => {
    const { container } = renderAvatar('Acme Legal');
    expect(screen.getByText('AL')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
  });

  it('renders the team picture when a URL is given', () => {
    const { container } = renderAvatar('Acme Legal', '/api/v1/teams/t1/avatar?v=1');
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/v1/teams/t1/avatar?v=1');
    expect(screen.queryByText('AL')).not.toBeInTheDocument();
  });

  it('falls back to the crest when the image fails to load', () => {
    const { container } = renderAvatar('Acme Legal', '/api/v1/teams/t1/avatar?v=1');
    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    fireEvent.error(img!);
    expect(screen.getByText('AL')).toBeInTheDocument();
  });

  it('handles a null name', () => {
    renderAvatar(null);
    expect(screen.getByText('?')).toBeInTheDocument();
  });
});
