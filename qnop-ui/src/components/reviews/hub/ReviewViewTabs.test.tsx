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
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { ReviewViewTabs } from './ReviewViewTabs';

function renderTabs(props: Partial<Parameters<typeof ReviewViewTabs>[0]> = {}) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter>
        <ReviewViewTabs
          documentId="d1"
          active="document"
          openTaskCount={2}
          compareEnabled
          {...props}
        />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

describe('ReviewViewTabs', () => {
  it('links every view and marks the active one', () => {
    renderTabs({ active: 'tasks' });

    // No ?view= param — the stored panel/focus preference decides (issue #403).
    expect(screen.getByTestId('review-view-tab-document')).toHaveAttribute('href', '/reviews/d1');
    // Focus is a toolbar-level presentation of the Document tab, not a tab (issue #403).
    expect(screen.queryByTestId('review-view-tab-focus')).not.toBeInTheDocument();
    expect(screen.getByTestId('review-view-tab-compare')).toHaveAttribute(
      'href',
      '/reviews/d1/compare',
    );
    const tasks = screen.getByTestId('review-view-tab-tasks');
    expect(tasks).toHaveAttribute('href', '/reviews/d1/tasks');
    expect(tasks).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('review-view-tab-document')).not.toHaveAttribute('aria-current');
  });

  it('shows the open-task pill only when a count is provided', () => {
    renderTabs({ openTaskCount: 3 });
    expect(within(screen.getByTestId('review-view-tab-tasks')).getByText('3')).toBeInTheDocument();
  });

  it('disables Compare until two versions are extracted', () => {
    renderTabs({ compareEnabled: false });
    const compare = screen.getByTestId('review-view-tab-compare');
    expect(compare).not.toHaveAttribute('href');
    expect(compare).toHaveAttribute('aria-disabled', 'true');
  });
});
