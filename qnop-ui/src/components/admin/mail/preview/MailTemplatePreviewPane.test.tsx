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

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { MailTemplatePreviewResponse } from '../../../../api/generated';
import { buildTheme } from '../../../../theme/theme';
import { MailTemplatePreviewPane } from './MailTemplatePreviewPane';

const PREVIEW: MailTemplatePreviewResponse = {
  subject: 'Hi qnop',
  bodyPlain: 'Body for Jane',
  bodyHtml: '<p>Hello Jane</p>',
  sampleVars: { siteName: 'qnop' },
};

type Props = Partial<Parameters<typeof MailTemplatePreviewPane>[0]>;

function renderPane(props: Props = {}) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MailTemplatePreviewPane
        status="live"
        preview={PREVIEW}
        error={null}
        onRefresh={vi.fn()}
        htmlEnabled
        placeholders={['siteName']}
        sampleValues={{ siteName: 'qnop' }}
        onSampleChange={vi.fn()}
        {...props}
      />
    </ThemeProvider>,
  );
}

describe('MailTemplatePreviewPane', () => {
  it('renders the HTML body in a fully sandboxed iframe', () => {
    renderPane();

    expect(screen.getByText('Hi qnop')).toBeTruthy();
    expect(screen.getByText('Body for Jane')).toBeTruthy();
    const iframe = screen.getByTitle('HTML preview');
    expect(iframe.getAttribute('sandbox')).toBe('');
    expect(iframe.getAttribute('srcdoc')).toContain('<p>Hello Jane</p>');
  });

  it('shows a fallback instead of an iframe when there is no HTML alternative', () => {
    renderPane({ htmlEnabled: false });

    expect(screen.queryByTitle('HTML preview')).toBeNull();
    expect(screen.getByText(/No HTML alternative/)).toBeTruthy();
  });

  it('edits a sample variable through the Variables popover', () => {
    const onSampleChange = vi.fn();
    renderPane({ onSampleChange });

    fireEvent.click(screen.getByRole('button', { name: 'Variables' }));
    fireEvent.change(screen.getByLabelText('siteName'), { target: { value: 'Acme' } });

    expect(onSampleChange).toHaveBeenCalledWith('siteName', 'Acme');
  });

  it('prompts for content when there is nothing to preview', () => {
    renderPane({ preview: null, status: 'idle' });

    expect(screen.getByText(/Add a subject/)).toBeTruthy();
    expect(screen.queryByTitle('HTML preview')).toBeNull();
  });

  it('surfaces a render error', () => {
    renderPane({ status: 'error', error: 'Template render failed' });

    expect(screen.getByText('Template render failed')).toBeTruthy();
  });
});
