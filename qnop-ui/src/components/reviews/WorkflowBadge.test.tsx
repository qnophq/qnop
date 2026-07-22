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
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { WorkflowBadge } from './WorkflowBadge';

function renderBadge(state: string, archivedAt?: string | null) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <WorkflowBadge state={state} archivedAt={archivedAt} />
    </ThemeProvider>,
  );
}

describe('WorkflowBadge', () => {
  it('renders the humanized workflow state', () => {
    renderBadge('IN_REVIEW');
    expect(screen.getByText('In review')).toBeInTheDocument();
  });

  it('reads as "Archived · <outcome>" when archived, preserving the terminal state (#576)', () => {
    renderBadge('FINALIZED', '2026-07-23T10:00:00Z');
    expect(screen.getByText('Archived · Finalized')).toBeInTheDocument();
    expect(screen.queryByText('Finalized')).not.toBeInTheDocument();
  });

  it('ignores a null archivedAt and shows the plain state', () => {
    renderBadge('CANCELLED', null);
    expect(screen.getByText('Cancelled')).toBeInTheDocument();
  });
});
