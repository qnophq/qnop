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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { AvatarUploader } from './AvatarUploader';

function renderUploader(props: Partial<React.ComponentProps<typeof AvatarUploader>> = {}) {
  const merged = {
    name: 'Ada Lovelace',
    imageUrl: null,
    onSelect: vi.fn(),
    onRemove: vi.fn(),
    ...props,
  };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('light')}>
        <AvatarUploader {...merged} />
      </ThemeProvider>,
    ),
    onSelect: merged.onSelect,
  };
}

function fileInput(container: HTMLElement): HTMLInputElement {
  return container.querySelector('input[type="file"]') as HTMLInputElement;
}

describe('AvatarUploader', () => {
  it('rejects a non-image file without selecting it', () => {
    const { container, onSelect } = renderUploader();
    const file = new File(['x'], 'note.txt', { type: 'text/plain' });
    fireEvent.change(fileInput(container), { target: { files: [file] } });
    expect(screen.getByText(/PNG, JPEG or WebP image/)).toBeTruthy();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('rejects an image larger than the cap', () => {
    const { container, onSelect } = renderUploader();
    const big = new File([new Uint8Array(1024 * 1024 + 1)], 'big.png', { type: 'image/png' });
    fireEvent.change(fileInput(container), { target: { files: [big] } });
    expect(screen.getByText(/larger than 1 MB/)).toBeTruthy();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('shows the remove action only when an avatar is set', () => {
    const { queryByText } = renderUploader({ imageUrl: null });
    expect(queryByText('Remove photo')).toBeNull();

    renderUploader({ imageUrl: '/api/v1/users/1/avatar?v=1' });
    expect(screen.getByText('Remove photo')).toBeTruthy();
  });
});
