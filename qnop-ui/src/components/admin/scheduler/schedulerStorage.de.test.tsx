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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import type { SchedulerJob } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { MissingBinariesTable } from '../storage/MissingBinariesTable';
import { OrphanedObjectsTable } from '../storage/OrphanedObjectsTable';
import { SchedulerJobCard } from './SchedulerJobCard';

function renderWithProviders(ui: ReactNode) {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MemoryRouter>{ui}</MemoryRouter>
    </ThemeProvider>,
  );
}

const REAPER: SchedulerJob = {
  jobId: 'storageOrphanReaper',
  displayName: 'Storage orphan reaper',
  description: 'Deletes uncommitted objects.',
  cron: '0 30 3 * * *',
  supportsDryRun: true,
  enabled: false,
  dryRun: true,
  lastOutcome: 'FAILURE',
};

/** The scheduler and storage-consistency surfaces in German (issue #836). */
describe('scheduler and storage in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the job card chrome in German, server-provided job text verbatim', () => {
    renderWithProviders(
      <SchedulerJobCard
        job={REAPER}
        saving={false}
        running={false}
        onToggleEnabled={() => undefined}
        onToggleDryRun={() => undefined}
        onRunNow={() => undefined}
        manualRunEnabled
        jobSettingsEditable
      />,
    );

    expect(screen.getByText('Fehlgeschlagen')).toBeInTheDocument();
    expect(screen.getByText('Deaktiviert')).toBeInTheDocument();
    expect(screen.getByText('Probelauf')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Jetzt ausführen' })).toBeInTheDocument();
    expect(
      screen.getByRole('switch', { name: 'Storage orphan reaper aktivieren' }),
    ).toBeInTheDocument();
    // Name, description and cron come from the server and stay as delivered.
    expect(screen.getByText('Deletes uncommitted objects.')).toBeInTheDocument();
    expect(screen.getByText('0 30 3 * * *')).toBeInTheDocument();
  });

  it('renders the storage tables with German headers, contexts and empty states', () => {
    renderWithProviders(
      <>
        <MissingBinariesTable
          binaries={[
            {
              kind: 'ATTACHMENT',
              storageKey: 'att/ab/cd',
              documentId: 'd1',
              documentSlug: 'doc',
              documentTitle: 'Spec',
              attachmentName: 'plan.pdf',
            } as never,
          ]}
        />
        <OrphanedObjectsTable
          objects={[]}
          selected={new Set()}
          onToggle={() => undefined}
          onToggleAll={() => undefined}
          onDeleteOne={() => undefined}
          busy={false}
        />
      </>,
    );

    expect(screen.getByRole('columnheader', { name: 'Speicherschlüssel' })).toBeInTheDocument();
    expect(screen.getByText('Anhang · plan.pdf')).toBeInTheDocument();
    expect(screen.getByText('Keine verwaisten Objekte.')).toBeInTheDocument();
    expect(
      screen.getByRole('checkbox', { name: 'Alle verwaisten Objekte auswählen' }),
    ).toBeInTheDocument();
  });
});
