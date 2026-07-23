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
import { WorkflowStateIcon } from './WorkflowStateIcon';

function renderIcon(state: string) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <WorkflowStateIcon state={state} />
    </ThemeProvider>,
  );
}

describe('WorkflowStateIcon', () => {
  it('labels every Community state with its readable name', () => {
    for (const [state, label] of [
      ['DRAFT', 'Draft'],
      ['IN_REVIEW', 'In review'],
      ['CHANGES_REQUESTED', 'Changes requested'],
      ['FINALIZED', 'Finalized'],
      ['CANCELLED', 'Cancelled'],
    ] as const) {
      renderIcon(state);
      expect(screen.getByRole('img', { name: label })).toBeInTheDocument();
    }
  });

  it('degrades an unknown (enterprise) state to a labelled neutral glyph', () => {
    renderIcon('LEGAL_HOLD');
    expect(screen.getByRole('img', { name: 'Legal hold' })).toBeInTheDocument();
  });
});
