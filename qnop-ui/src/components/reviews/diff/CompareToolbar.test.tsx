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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { DocumentVersionSummary } from '../../../api/generated';
import { ExtractionStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { CompareToolbar } from './CompareToolbar';

const version = (
  versionNumber: number,
  extractionStatus: ExtractionStatus = ExtractionStatus.Ready,
): DocumentVersionSummary => ({
  versionNumber,
  contentType: 'application/pdf',
  sizeBytes: 1000,
  contentHash: `hash-${versionNumber}`,
  extractionStatus,
  createdBy: 'u1',
  createdAt: '2026-07-01T10:00:00Z',
});

function renderToolbar(overrides: Partial<Parameters<typeof CompareToolbar>[0]> = {}) {
  const props: Parameters<typeof CompareToolbar>[0] = {
    versions: [version(1), version(2), version(3, ExtractionStatus.Pending)],
    from: 1,
    to: 2,
    onChangePair: vi.fn(),
    syncScroll: true,
    onSyncScrollChange: vi.fn(),
    changeCount: 4,
    ...overrides,
  };
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <CompareToolbar {...props} />
    </ThemeProvider>,
  );
  return props;
}

describe('CompareToolbar', () => {
  it('shows the change count', () => {
    renderToolbar();
    expect(screen.getByText('4 changes')).toBeInTheDocument();
  });

  it('shows a comparing state while the diff loads', () => {
    renderToolbar({ changeCount: null });
    expect(screen.getByText('Comparing…')).toBeInTheDocument();
  });

  it('disables unextracted versions and the other side in a picker', () => {
    renderToolbar();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Baseline' }));
    const listbox = within(screen.getByRole('listbox'));
    expect(listbox.getByRole('option', { name: 'v3' })).toHaveAttribute('aria-disabled', 'true');
    expect(listbox.getByRole('option', { name: 'v2' })).toHaveAttribute('aria-disabled', 'true');
    expect(listbox.getByRole('option', { name: 'v1' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('swaps the pair', () => {
    const props = renderToolbar();
    fireEvent.click(screen.getByRole('button', { name: 'Swap the compared versions' }));
    expect(props.onChangePair).toHaveBeenCalledWith(2, 1);
  });

  it('toggles sync scroll', () => {
    const props = renderToolbar();
    fireEvent.click(screen.getByRole('switch', { name: 'Sync scroll' }));
    expect(props.onSyncScrollChange).toHaveBeenCalledWith(false);
  });
});
