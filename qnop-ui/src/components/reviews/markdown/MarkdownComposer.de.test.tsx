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

import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { MarkdownComposer } from './MarkdownComposer';
import type { UploadedAttachment } from './useCommentAttachmentUpload';

function Host({
  onUploadAttachment,
}: {
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
}) {
  const [value, setValue] = useState('');
  return (
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <MarkdownComposer
        value={value}
        onChange={setValue}
        onUploadAttachment={onUploadAttachment}
        onToggleFullscreen={() => undefined}
        inputAriaLabel="Kommentar"
      />
    </ThemeProvider>
  );
}

/** The comment composer in German (issue #836): toolbar, modes, hints and the upload placeholder. */
describe('MarkdownComposer in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('names the formatting toolbar, the mode switch and the footer affordances in German', () => {
    render(<Host onUploadAttachment={vi.fn()} />);

    expect(screen.getByRole('toolbar', { name: 'Textformatierung' })).toBeInTheDocument();
    for (const name of [
      'Fett',
      'Kursiv',
      'Durchgestrichen',
      'Überschrift',
      'Zitat',
      'Link',
      'Bild',
      'Tabelle',
      'Nummerierte Liste',
      'Aufzählung',
      'Code',
      'Codeblock',
    ]) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Schreiben' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Vollbild' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Emoji einfügen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Datei anhängen' })).toBeInTheDocument();
    expect(screen.getByText('Markdown wird unterstützt')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Kommentar hinzufügen')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Vorschau' }));
    expect(screen.getByText('Nichts für die Vorschau.')).toBeInTheDocument();
  });

  it('marks a running upload with a German placeholder', () => {
    render(<Host onUploadAttachment={() => new Promise(() => undefined)} />);

    const file = new File(['bytes'], 'bericht.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });

    expect((screen.getByLabelText('Kommentar') as HTMLTextAreaElement).value).toContain(
      '[bericht.pdf wird hochgeladen…]()',
    );
  });
});
