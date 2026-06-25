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
import { UserAvatar } from './UserAvatar';

function renderAvatar(ui: React.ReactElement) {
  return render(<ThemeProvider theme={buildTheme('light')}>{ui}</ThemeProvider>);
}

describe('UserAvatar', () => {
  it('shows initials when no image URL is set', () => {
    const { container } = renderAvatar(<UserAvatar name="Ada Lovelace" />);
    expect(screen.getByText('AL')).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });

  it('renders the uploaded image when a URL is provided', () => {
    const { container } = renderAvatar(
      <UserAvatar name="Ada Lovelace" imageUrl="/api/v1/users/1/avatar?v=7" />,
    );
    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    expect(img?.getAttribute('src')).toBe('/api/v1/users/1/avatar?v=7');
  });

  it('falls back to initials when the image fails to load', () => {
    const { container } = renderAvatar(<UserAvatar name="Ada Lovelace" imageUrl="/broken" />);
    fireEvent.error(container.querySelector('img') as HTMLImageElement);
    expect(screen.getByText('AL')).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });
});
