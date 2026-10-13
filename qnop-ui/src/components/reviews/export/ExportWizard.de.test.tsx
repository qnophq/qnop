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
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { ExportWizard } from './ExportWizard';
import { EXPORT_FIELDS } from './exportModel';

function renderWizard() {
  return render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <ExportWizard
        open
        onClose={vi.fn()}
        onExport={vi.fn()}
        documentTitle="Vertrag"
        counts={{ all: 3, open: 1, resolved: 2 }}
      />
    </ThemeProvider>,
  );
}

/** Every rendered string came from the catalog — a missing key would show as "export.…". */
function expectNoRawKeys() {
  expect(document.body.textContent).not.toMatch(/\bexport\.[a-zA-Z]/);
}

/**
 * The export wizard in German (issue #836). Its group headings and the format's vocabulary are
 * looked up through template-literal keys, which the type checker cannot vouch for — so the
 * rendered text is checked for raw keys on both steps and for both vocabularies.
 */
describe('export wizard in German', () => {
  beforeEach(async () => {
    localStorage.clear();
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders step 1 and the commitment line for a spreadsheet', () => {
    renderWizard();

    expect(screen.getByText('Was brauchst du?')).toBeInTheDocument();
    expect(screen.getByText('Welche Anmerkungen')).toBeInTheDocument();
    expect(screen.getByText('Nur offene')).toBeInTheDocument();
    expect(
      screen.getByText(
        `Excel .xlsx · ${EXPORT_FIELDS.length} von ${EXPORT_FIELDS.length} Spalten · 3 Anmerkungen · mit Diskussionen`,
      ),
    ).toBeInTheDocument();
    expectNoRawKeys();
  });

  it('renders the field groups and the column vocabulary on step 2', () => {
    renderWizard();
    fireEvent.click(screen.getByRole('button', { name: /Weiter/ }));

    expect(screen.getByText('Welche Details?')).toBeInTheDocument();
    expect(screen.getByText('Spalten')).toBeInTheDocument();
    for (const group of ['Kennung', 'Einordnung', 'Verlauf']) {
      expect(screen.getByText(group)).toBeInTheDocument();
    }
    // "Inhalt" names both the second step and the content group.
    expect(screen.getAllByText('Inhalt')).toHaveLength(2);
    expect(screen.getByText('Aufgabenschlüssel (#)')).toBeInTheDocument();
    expect(screen.getByText('Europäisch')).toBeInTheDocument();
    expectNoRawKeys();
  });

  it('switches to the report vocabulary for Word', () => {
    renderWizard();
    fireEvent.click(screen.getByRole('button', { name: /Word/ }));

    expect(screen.getByText(/von \d+ Details · 3 Anmerkungen/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Weiter/ }));
    expect(screen.getByText('Details')).toBeInTheDocument();
    expectNoRawKeys();
  });
});
