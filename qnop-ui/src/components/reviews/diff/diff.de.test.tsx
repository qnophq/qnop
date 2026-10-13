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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { DiffChange, DocumentVersionSummary } from '../../../api/generated';
import { DiffChangeType, ExtractionStatus } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { ChangeSummaryPanel } from './ChangeSummaryPanel';
import { CompareToolbar } from './CompareToolbar';

const box = { x: 0.1, y: 0.1, width: 0.2, height: 0.02 };
const CHANGES: DiffChange[] = [
  {
    type: DiffChangeType.Changed,
    fromText: 'vierzehn Tage',
    toText: 'dreißig Tage',
    fromLocations: [{ surfaceIndex: 0, box }],
    toLocations: [{ surfaceIndex: 0, box }],
  },
  {
    type: DiffChangeType.Inserted,
    fromText: '',
    toText: 'eine neue Klausel',
    fromLocations: [],
    toLocations: [{ surfaceIndex: 2, box }],
  },
];

const version = (versionNumber: number): DocumentVersionSummary => ({
  versionNumber,
  contentType: 'application/pdf',
  sizeBytes: 1000,
  contentHash: `hash-${versionNumber}`,
  extractionStatus: ExtractionStatus.Ready,
  createdBy: 'u1',
  createdAt: '2026-07-01T10:00:00Z',
});

const theme = buildTheme('light', 'de');

/** The version comparison in German (issue #836): toolbar statistics and the change legend. */
describe('version comparison in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('labels the pickers, statistics and switches of the toolbar', () => {
    render(
      <ThemeProvider theme={theme}>
        <CompareToolbar
          versions={[version(1), version(2)]}
          from={1}
          to={2}
          onChangePair={vi.fn()}
          syncScroll
          onSyncScrollChange={vi.fn()}
          changes={CHANGES}
          zoom={1}
          onZoomChange={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(screen.getByLabelText('Ausgangsversion')).toBeInTheDocument();
    expect(screen.getByLabelText('Vergleich')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Die verglichenen Versionen tauschen' }),
    ).toBeVisible();
    const stats = within(screen.getByTestId('toolbar-diff-stats'));
    expect(stats.getByText('Wörter +')).toBeInTheDocument();
    expect(stats.getByText('Wörter −')).toBeInTheDocument();
    expect(stats.getByText('Änderungen')).toBeInTheDocument();
    expect(stats.getByText('Seiten')).toBeInTheDocument();
    expect(screen.getByLabelText('Synchron scrollen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verkleinern' })).toBeInTheDocument();
  });

  it('switches the page statistic to the singular for one page', () => {
    render(
      <ThemeProvider theme={theme}>
        <CompareToolbar
          versions={[version(1), version(2)]}
          from={1}
          to={2}
          onChangePair={vi.fn()}
          syncScroll={false}
          onSyncScrollChange={vi.fn()}
          changes={[CHANGES[0]]}
          zoom={1}
          onZoomChange={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(within(screen.getByTestId('toolbar-diff-stats')).getByText('Seite')).toBeVisible();
  });

  it('names each change kind and page in the summary rail', () => {
    render(
      <ThemeProvider theme={theme}>
        <ChangeSummaryPanel
          changes={CHANGES}
          activeChangeIndex={null}
          onSelectChange={vi.fn()}
          onCollapse={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(screen.getByText('Änderungen (2)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Änderungsleiste einklappen' })).toBeVisible();
    expect(within(screen.getByTestId('change-card-0')).getByText('Geändert')).toBeVisible();
    expect(within(screen.getByTestId('change-card-1')).getByText('Hinzugefügt')).toBeVisible();
    expect(within(screen.getByTestId('change-card-1')).getByText('S. 3')).toBeVisible();
  });

  it('reports identical versions in German', () => {
    render(
      <ThemeProvider theme={theme}>
        <ChangeSummaryPanel changes={[]} activeChangeIndex={null} onSelectChange={vi.fn()} />
      </ThemeProvider>,
    );
    expect(
      screen.getByText('Die verglichenen Versionen haben denselben Textinhalt.'),
    ).toBeInTheDocument();
  });
});
