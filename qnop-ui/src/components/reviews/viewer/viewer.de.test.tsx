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
import { cleanup, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { ReattachHintBar } from './ReattachHintBar';
import { ViewerToolbar } from './ViewerToolbar';

const theme = buildTheme('light', 'de');

/** The viewer chrome in German (issue #836): toolbar controls and the re-attach hint. */
describe('viewer in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('names the toolbar controls in German', () => {
    render(
      <ThemeProvider theme={theme}>
        <ViewerToolbar
          versions={[{ versionNumber: 1 } as never]}
          currentVersion={1}
          onVersionChange={vi.fn()}
          currentPage={1}
          pageCount={3}
          onNavigateToPage={vi.fn()}
          tool="text"
          onToolChange={vi.fn()}
          textToolAvailable
          canAnnotate
          zoom={1}
          onZoomChange={vi.fn()}
          viewMode="focus"
          onViewModeChange={vi.fn()}
          annotationCount={4}
          onOpenAnnotationList={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(screen.getByText('Seite 2 / 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Vorherige Seite' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Anmerkungswerkzeug' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Text markieren' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bereich aufziehen' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Dokumentlayout' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Anmerkungen anzeigen (4)' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Zoom auf Seitenbreite zurücksetzen' }),
    ).toBeInTheDocument();
  });

  it('quotes the passage being re-homed with German quotation marks', () => {
    render(
      <ThemeProvider theme={theme}>
        <ReattachHintBar excerpt="<b>die Klausel</b>" onCancel={vi.fn()} />
      </ThemeProvider>,
    );

    // The passage is data: it renders as text, never as markup.
    expect(screen.getByTestId('reattach-hint')).toHaveTextContent(
      '„<b>die Klausel</b>“ wird neu verankert — markiere die neue Textstelle',
    );
    expect(screen.getByRole('button', { name: 'Neuverankern abbrechen' })).toBeInTheDocument();
  });
});
