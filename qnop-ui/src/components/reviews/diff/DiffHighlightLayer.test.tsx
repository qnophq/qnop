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
import { DiffHighlightLayer } from './DiffHighlightLayer';

const CHANGES: DiffChange[] = [
  {
    type: DiffChangeType.Deleted,
    fromText: 'gone',
    toText: '',
    fromLocations: [{ surfaceIndex: 0, box: { x: 0.1, y: 0.2, width: 0.3, height: 0.02 } }],
    toLocations: [],
  },
  {
    type: DiffChangeType.Inserted,
    fromText: '',
    toText: 'fresh',
    fromLocations: [],
    toLocations: [{ surfaceIndex: 0, box: { x: 0.4, y: 0.5, width: 0.2, height: 0.02 } }],
  },
  {
    type: DiffChangeType.Changed,
    fromText: 'old',
    toText: 'new',
    fromLocations: [{ surfaceIndex: 1, box: { x: 0.1, y: 0.1, width: 0.1, height: 0.02 } }],
    toLocations: [{ surfaceIndex: 0, box: { x: 0.1, y: 0.7, width: 0.1, height: 0.02 } }],
  },
];

function renderLayer(side: 'from' | 'to', surfaceIndex = 0, active: number | null = null) {
  const onSelectChange = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <DiffHighlightLayer
        changes={CHANGES}
        side={side}
        surfaceIndex={surfaceIndex}
        activeChangeIndex={active}
        onSelectChange={onSelectChange}
      />
    </ThemeProvider>,
  );
  return onSelectChange;
}

describe('DiffHighlightLayer', () => {
  it('paints deletions only on the baseline pane', () => {
    renderLayer('from');
    expect(screen.getByTestId('diff-highlight-from-0')).toBeInTheDocument();
    expect(screen.queryByTestId('diff-highlight-from-1')).not.toBeInTheDocument();
    // The changed change's from-geometry lives on surface 1, not this one.
    expect(screen.queryByTestId('diff-highlight-from-2')).not.toBeInTheDocument();
  });

  it('paints insertions and in-place changes on the newer pane', () => {
    renderLayer('to');
    expect(screen.queryByTestId('diff-highlight-to-0')).not.toBeInTheDocument();
    expect(screen.getByTestId('diff-highlight-to-1')).toBeInTheDocument();
    expect(screen.getByTestId('diff-highlight-to-2')).toBeInTheDocument();
  });

  it('reports a click with the change index and marks the active change', () => {
    const onSelectChange = renderLayer('to', 0, 2);
    fireEvent.click(screen.getByTestId('diff-highlight-to-1'));
    expect(onSelectChange).toHaveBeenCalledWith(1);
    expect(screen.getByTestId('diff-highlight-to-2')).toHaveAttribute('aria-pressed', 'true');
  });

  it('positions the band from the located box', () => {
    renderLayer('from');
    const band = screen.getByTestId('diff-highlight-from-0');
    expect(band).toHaveStyle({ left: '10%', width: '30%' });
  });
});
