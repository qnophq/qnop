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
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../../../i18n';
import { buildTheme } from '../../../../theme/theme';
import { MailTemplatePreviewPane } from './MailTemplatePreviewPane';

/** The mail-template live preview in German (issue #836): chrome translated, template data not. */
describe('MailTemplatePreviewPane in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders German chrome around the untranslated template output', () => {
    render(
      <ThemeProvider theme={buildTheme('light', 'de')}>
        <MailTemplatePreviewPane
          status="live"
          preview={{ subject: 'Hi qnop', bodyPlain: 'Body', bodyHtml: undefined, sampleVars: {} }}
          error={null}
          onRefresh={vi.fn()}
          htmlEnabled={false}
          placeholders={['siteName']}
          sampleValues={{ siteName: 'qnop' }}
          onSampleChange={vi.fn()}
        />
      </ThemeProvider>,
    );

    expect(screen.getByRole('heading', { name: 'Live-Vorschau' })).toBeInTheDocument();
    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Vorschau aktualisieren' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Variablen' })).toBeInTheDocument();
    expect(screen.getByText(/Keine HTML-Alternative — Empfänger/)).toBeInTheDocument();
    expect(screen.getByText('Hi qnop')).toBeInTheDocument();
  });
});
