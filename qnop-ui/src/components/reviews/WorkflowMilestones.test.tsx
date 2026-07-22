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
import { WorkflowMilestones } from './WorkflowMilestones';
import { milestoneIndex } from './workflowMeta';

function renderStrip(
  props: Parameters<typeof WorkflowMilestones>[0],
  mode: 'light' | 'dark' = 'light',
) {
  return render(
    <ThemeProvider theme={buildTheme(mode)}>
      <WorkflowMilestones {...props} />
    </ThemeProvider>,
  );
}

describe('milestoneIndex', () => {
  it('charts the Community states and rejects unknown ones', () => {
    expect(milestoneIndex('DRAFT')).toBe(0);
    expect(milestoneIndex('IN_REVIEW')).toBe(1);
    expect(milestoneIndex('CHANGES_REQUESTED')).toBe(1);
    expect(milestoneIndex('FINALIZED')).toBe(2);
    expect(milestoneIndex('CANCELLED')).toBe('cancelled');
    expect(milestoneIndex('ENTERPRISE_GATE')).toBeNull();
  });
});

describe('WorkflowMilestones', () => {
  it('renders the live stage with progress for an in-review document', () => {
    renderStrip({ state: 'IN_REVIEW', total: 5, resolved: 2 });

    const strip = screen.getByTestId('workflow-milestones');
    expect(strip).toHaveAccessibleName('In review — 2 of 5 annotations resolved, 3 to go');
    expect(strip).toHaveTextContent('In review');
    expect(strip).toHaveTextContent('2/5');
  });

  it('ping-pongs the derived pair as one stage — changes requested stays mid-path', () => {
    renderStrip({ state: 'CHANGES_REQUESTED', total: 3, resolved: 3 });

    const strip = screen.getByTestId('workflow-milestones');
    expect(strip).toHaveTextContent('Changes requested');
    expect(strip).toHaveAccessibleName(/ready to finalize/);
  });

  it('celebrates the finalized arrival', () => {
    renderStrip({ state: 'FINALIZED' });

    const strip = screen.getByTestId('workflow-milestones');
    expect(strip).toHaveTextContent('Finalized');
    expect(strip).toHaveAccessibleName('Review finalized — every concern settled');
  });

  it('renders the cancelled side exit with the auto-close hint', () => {
    renderStrip({ state: 'CANCELLED', total: 4, resolved: 1 });

    const strip = screen.getByTestId('workflow-milestones');
    expect(strip).toHaveTextContent('Cancelled');
    expect(strip).toHaveAccessibleName(
      'Review cancelled — 3 annotations were closed automatically',
    );
  });

  it('announces a draft as not yet in review, in both themes', () => {
    renderStrip({ state: 'DRAFT' });
    expect(screen.getByTestId('workflow-milestones')).toHaveAccessibleName(
      'Draft — not yet in review',
    );

    renderStrip({ state: 'DRAFT' }, 'dark');
    expect(screen.getAllByTestId('workflow-milestones')[1]).toHaveAccessibleName(
      'Draft — not yet in review',
    );
  });

  it('falls back to the flat badge for an unknown (enterprise) state', () => {
    renderStrip({ state: 'LEGAL_HOLD' });

    expect(screen.queryByTestId('workflow-milestones')).toBeNull();
    expect(screen.getByText('Legal hold')).toBeInTheDocument();
  });
});
