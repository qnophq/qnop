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

import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { SchedulerJob } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { SchedulerJobCard } from './SchedulerJobCard';

const BASE: SchedulerJob = {
  jobId: 'storageOrphanReaper',
  displayName: 'Storage orphan reaper',
  description: 'Deletes uncommitted objects.',
  cron: '0 30 3 * * *',
  supportsDryRun: true,
  enabled: true,
  dryRun: false,
};

function wrapper({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>;
}

function renderCard(job: SchedulerJob, handlers = {}) {
  const props = {
    job,
    saving: false,
    running: false,
    onToggleEnabled: vi.fn(),
    onToggleDryRun: vi.fn(),
    onRunNow: vi.fn(),
    manualRunEnabled: true,
    jobSettingsEditable: true,
    ...handlers,
  };
  render(<SchedulerJobCard {...props} />, { wrapper });
  return props;
}

describe('SchedulerJobCard', () => {
  it('shows the cron and a dry-run switch for a capable job', () => {
    renderCard(BASE);
    expect(screen.getByText('0 30 3 * * *')).toBeTruthy();
    expect(screen.getByLabelText('Dry-run Storage orphan reaper')).toBeTruthy();
  });

  it('hides the dry-run switch for a job that does not support it', () => {
    renderCard({ ...BASE, supportsDryRun: false });
    expect(screen.queryByLabelText('Dry-run Storage orphan reaper')).toBeNull();
  });

  it('renders a success badge with the last-run detail for a successful run', () => {
    renderCard({
      ...BASE,
      lastOutcome: 'SUCCESS',
      lastTrigger: 'MANUAL',
      lastRunAt: '2026-01-01T00:00:00Z',
    });
    expect(screen.getByText('Success')).toBeTruthy();
    expect(screen.getByText(/Ran manually/)).toBeTruthy();
  });

  it('surfaces the failure detail for a failed run', () => {
    renderCard({ ...BASE, lastOutcome: 'FAILURE', lastDetail: 'IllegalStateException: boom' });
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getByText('IllegalStateException: boom')).toBeTruthy();
  });

  it('surfaces the run summary for a successful run too (#577 follow-up)', () => {
    renderCard({
      ...BASE,
      lastOutcome: 'SUCCESS',
      lastRunAt: '2026-01-01T00:00:00Z',
      lastDetail: 'Would purge 2 review(s) and 2 storage object(s); nothing was changed',
    });
    expect(
      screen.getByText('— Would purge 2 review(s) and 2 storage object(s); nothing was changed'),
    ).toBeTruthy();
  });

  it('shows "Never run" when the job has no history', () => {
    renderCard(BASE);
    expect(screen.getByText('Never run')).toBeTruthy();
  });

  it('invokes the handlers on interaction', () => {
    const onToggleEnabled = vi.fn();
    const onRunNow = vi.fn();
    renderCard(BASE, { onToggleEnabled, onRunNow });

    fireEvent.click(screen.getByLabelText('Enable Storage orphan reaper'));
    fireEvent.click(screen.getByRole('button', { name: 'Run now' }));

    expect(onToggleEnabled).toHaveBeenCalledWith(BASE);
    expect(onRunNow).toHaveBeenCalledWith(BASE);
  });

  it('offers no run-now button where the deployment forbids manual runs', () => {
    // Issue #676: dropped rather than disabled — the page says why once, at the
    // top, so a greyed-out button here would only invite a hover for nothing.
    renderCard(BASE, { manualRunEnabled: false });

    expect(screen.queryByRole('button', { name: 'Run now' })).not.toBeInTheDocument();
    // The rest of the card still works: what is withheld is the manual trigger.
    expect(screen.getByLabelText('Enable Storage orphan reaper')).toBeTruthy();
  });

  it('renders the switches read-only where the deployment fixes job settings', () => {
    // Issue #677: kept, not removed — a switch says whether the job is on, which
    // stays worth reading when it cannot be flipped. That is the opposite call
    // from the run button, which said nothing once it could not be pressed.
    renderCard(BASE, { jobSettingsEditable: false });

    expect(screen.getByLabelText('Enable Storage orphan reaper')).toBeDisabled();
    expect(screen.getByLabelText('Dry-run Storage orphan reaper')).toBeDisabled();
    // Still legible: the state is what the operator came to check.
    expect(screen.getByText('Enabled')).toBeInTheDocument();
  });
});
