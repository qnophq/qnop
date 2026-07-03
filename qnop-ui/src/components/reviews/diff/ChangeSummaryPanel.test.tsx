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
import type { DiffChange } from '../../../api/generated';
import { DiffChangeType } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { ChangeSummaryPanel } from './ChangeSummaryPanel';

const CHANGES: DiffChange[] = [
  {
    type: DiffChangeType.Changed,
    fromText: 'fourteen days',
    toText: 'thirty days',
    fromLocations: [{ surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.02 } }],
    toLocations: [{ surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.02 } }],
  },
  {
    type: DiffChangeType.Deleted,
    fromText: 'the prepayment clause',
    toText: '',
    fromLocations: [{ surfaceIndex: 2, box: { x: 0.1, y: 0.4, width: 0.4, height: 0.02 } }],
    toLocations: [],
  },
];

function renderPanel(changes: DiffChange[] = CHANGES, active: number | null = null) {
  const onSelectChange = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ChangeSummaryPanel
        changes={changes}
        activeChangeIndex={active}
        onSelectChange={onSelectChange}
      />
    </ThemeProvider>,
  );
  return onSelectChange;
}

describe('ChangeSummaryPanel', () => {
  it('renders one card per change with type label, excerpt and page', () => {
    renderPanel();
    expect(screen.getByText('Changes (2)')).toBeInTheDocument();
    expect(screen.getByText('Changed')).toBeInTheDocument();
    expect(screen.getByText('Deleted')).toBeInTheDocument();
    expect(screen.getByText('fourteen days')).toBeInTheDocument();
    expect(screen.getByText('thirty days')).toBeInTheDocument();
    expect(screen.getByText('the prepayment clause')).toBeInTheDocument();
    expect(screen.getByText('p. 1')).toBeInTheDocument();
    expect(screen.getByText('p. 3')).toBeInTheDocument();
  });

  it('selects a change on click and clears it when clicked again', () => {
    const onSelectChange = renderPanel();
    fireEvent.click(screen.getByTestId('change-card-1'));
    expect(onSelectChange).toHaveBeenCalledWith(1);
  });

  it('clears the active change when its card is clicked again', () => {
    const onSelectChange = renderPanel(CHANGES, 1);
    fireEvent.click(screen.getByTestId('change-card-1'));
    expect(onSelectChange).toHaveBeenCalledWith(null);
    expect(screen.getByTestId('change-card-1')).toHaveAttribute('aria-pressed', 'true');
  });

  it('states identical content for an empty diff', () => {
    renderPanel([]);
    expect(screen.getByText(/identical text content/)).toBeInTheDocument();
  });
});
