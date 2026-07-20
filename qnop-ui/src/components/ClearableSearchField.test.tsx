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
import type { ReactNode } from 'react';
import { buildTheme } from '../theme/theme';
import { ClearableSearchField } from './ClearableSearchField';

function wrapper({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>;
}

describe('ClearableSearchField', () => {
  it('shows no clear button while the field is empty', () => {
    render(<ClearableSearchField value="" onValueChange={vi.fn()} />, { wrapper });

    expect(screen.queryByRole('button', { name: 'Clear search' })).toBeNull();
  });

  it('propagates typed input through onValueChange', () => {
    const onValueChange = vi.fn();
    render(<ClearableSearchField value="" onValueChange={onValueChange} />, { wrapper });

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'qnop' } });

    expect(onValueChange).toHaveBeenCalledWith('qnop');
  });

  it('clears via onValueChange by default once text is present', () => {
    const onValueChange = vi.fn();
    render(<ClearableSearchField value="qnop" onValueChange={onValueChange} />, { wrapper });

    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));

    expect(onValueChange).toHaveBeenCalledWith('');
  });

  it('prefers a custom onClear and clearLabel', () => {
    const onValueChange = vi.fn();
    const onClear = vi.fn();
    render(
      <ClearableSearchField
        value="qnop"
        onValueChange={onValueChange}
        onClear={onClear}
        clearLabel="Clear filter"
      />,
      { wrapper },
    );

    fireEvent.click(screen.getByRole('button', { name: 'Clear filter' }));

    expect(onClear).toHaveBeenCalled();
    expect(onValueChange).not.toHaveBeenCalled();
  });

  it('applies inputAriaLabel and maxLength to the input element', () => {
    render(
      <ClearableSearchField
        value=""
        onValueChange={vi.fn()}
        inputAriaLabel="Search users"
        maxLength={256}
      />,
      { wrapper },
    );

    const input = screen.getByRole('textbox', { name: 'Search users' });
    expect(input.getAttribute('maxlength')).toBe('256');
  });
});
