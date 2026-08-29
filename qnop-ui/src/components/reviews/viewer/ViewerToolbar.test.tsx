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
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import type { ReviewViewMode } from '../focus/useViewMode';
import { ViewerToolbar } from './ViewerToolbar';

function renderToolbar(viewMode: ReviewViewMode = 'panel') {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ViewerToolbar
        versions={[{ versionNumber: 1 } as never]}
        currentVersion={1}
        onVersionChange={vi.fn()}
        currentPage={0}
        pageCount={3}
        onNavigateToPage={vi.fn()}
        tool="text"
        onToolChange={vi.fn()}
        textToolAvailable
        canAnnotate
        zoom={1}
        onZoomChange={vi.fn()}
        viewMode={viewMode}
        onViewModeChange={vi.fn()}
        annotationCount={4}
        onOpenAnnotationList={vi.fn()}
      />
    </ThemeProvider>,
  );
}

/**
 * Locks the trailing cluster's structure (issue #772): both sub-groups exist
 * with their controls, and both dividers are present — the second one hides
 * below `sm` via CSS only, so wrapping never leaves a dangling divider.
 */
describe('ViewerToolbar', () => {
  it('keeps both trailing sub-groups and their separators', () => {
    renderToolbar();

    expect(screen.getByRole('group', { name: 'Annotation tool' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Document layout' })).toBeInTheDocument();
    expect(screen.getAllByRole('separator')).toHaveLength(2);
  });

  it('shows the annotation-list entry point only in focus mode', () => {
    renderToolbar('focus');
    expect(screen.getByRole('button', { name: 'Show annotations (4)' })).toBeInTheDocument();
  });

  it('hides the annotation-list entry point in panel mode', () => {
    renderToolbar('panel');
    expect(screen.queryByRole('button', { name: /Show annotations/ })).toBeNull();
  });
});
