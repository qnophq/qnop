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
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import { MarkdownComposer } from './MarkdownComposer';
import type { UploadedAttachment } from './useCommentAttachmentUpload';

/** Hosts the controlled composer the way the real callers do. */
function Host({
  onSubmit,
  onUploadAttachment,
  onToggleFullscreen,
  initial = '',
  mentionCandidates,
}: {
  onSubmit?: () => void;
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
  onToggleFullscreen?: () => void;
  initial?: string;
  mentionCandidates?: { id: string; name: string }[];
}) {
  const [value, setValue] = useState(initial);
  return (
    <ThemeProvider theme={buildTheme('light')}>
      <MarkdownComposer
        value={value}
        onChange={setValue}
        onSubmit={onSubmit}
        onUploadAttachment={onUploadAttachment}
        onToggleFullscreen={onToggleFullscreen}
        inputAriaLabel="Test comment"
        mentionCandidates={mentionCandidates}
      />
    </ThemeProvider>
  );
}

function textarea(): HTMLTextAreaElement {
  return screen.getByLabelText('Test comment');
}

describe('MarkdownComposer', () => {
  it('offers the roster on @ and inserts the canonical mention token on pick (#462)', () => {
    render(
      <Host
        mentionCandidates={[
          { id: '018f5a3e-0000-7000-8000-000000000001', name: 'Alice' },
          { id: '018f5a3e-0000-7000-8000-000000000002', name: 'Bob' },
        ]}
      />,
    );
    const ta = textarea();
    ta.focus();
    fireEvent.change(ta, { target: { value: 'hi @Al' } });
    ta.setSelectionRange(6, 6);
    fireEvent.keyUp(ta, { key: 'l' });

    // The picker offers only the roster match for the "@Al" query.
    const option = screen.getByText('Alice');
    expect(option).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();

    fireEvent.mouseDown(option); // mousedown selects without blurring the field
    expect(ta.value).toContain('[@Alice](mention:018f5a3e-0000-7000-8000-000000000001)');
  });

  it('offers no @ picker without a roster (e.g. anonymous reviews) (#462)', () => {
    render(<Host />);
    const ta = textarea();
    ta.focus();
    fireEvent.change(ta, { target: { value: 'hi @Al' } });
    ta.setSelectionRange(6, 6);
    fireEvent.keyUp(ta, { key: 'l' });

    expect(screen.queryByTestId('mention-option')).not.toBeInTheDocument();
  });

  it('renders the Slack formatting set, the emoji affordance and the Markdown hint', () => {
    render(<Host />);

    for (const label of [
      'Bold',
      'Italic',
      'Strikethrough',
      'Heading',
      'Blockquote',
      'Link',
      'Image',
      'Table',
      'Ordered list',
      'Bulleted list',
      'Code',
      'Code block',
    ]) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Insert emoji' })).toBeInTheDocument();
    expect(screen.getByTestId('markdown-hint')).toBeInTheDocument();
  });

  it('wraps the selection when a toolbar button is clicked (jsdom fallback path)', () => {
    render(<Host initial="make this bold" />);
    const field = textarea();
    field.focus();
    field.setSelectionRange(10, 14);

    fireEvent.click(screen.getByRole('button', { name: 'Bold' }));

    expect(field.value).toBe('make this **bold**');
    expect(field.selectionStart).toBe(12);
    expect(field.selectionEnd).toBe(16);
  });

  it('applies formatting through the keyboard shortcut', () => {
    render(<Host initial="emphasise me" />);
    const field = textarea();
    field.focus();
    field.setSelectionRange(0, 9);

    fireEvent.keyDown(field, { key: 'i', metaKey: true });

    expect(field.value).toBe('_emphasise_ me');
  });

  it('fires onSubmit for the platform submit chord', () => {
    const onSubmit = vi.fn();
    render(<Host onSubmit={onSubmit} initial="ready" />);

    fireEvent.keyDown(textarea(), { key: 'Enter', metaKey: true });

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('does not submit on a plain Enter', () => {
    const onSubmit = vi.fn();
    render(<Host onSubmit={onSubmit} initial="ready" />);

    fireEvent.keyDown(textarea(), { key: 'Enter' });

    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('MarkdownComposer — preview mode (issue #445 follow-up)', () => {
  it('renders the draft as Markdown in preview and returns to the editable field', () => {
    render(<Host initial="some **bold** text" />);

    fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    // The rendered draft replaces the textarea; the formatting toolbar hides.
    const preview = screen.getByTestId('composer-preview');
    expect(preview.querySelector('strong')).toHaveTextContent('bold');
    expect(screen.queryByLabelText('Test comment')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Bold' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Write' }));
    expect(screen.getByLabelText('Test comment')).toHaveValue('some **bold** text');
    expect(screen.getByRole('button', { name: 'Bold' })).toBeInTheDocument();
  });

  it('shows a quiet empty state when there is nothing to preview', () => {
    render(<Host />);

    fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    expect(screen.getByTestId('composer-preview')).toHaveTextContent('Nothing to preview.');
  });

  it('disables the emoji affordance while previewing', () => {
    render(<Host initial="draft" />);

    fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    expect(screen.getByRole('button', { name: 'Insert emoji' })).toBeDisabled();
  });
});

describe('MarkdownComposer — attachments (issue #446)', () => {
  const uploaded: UploadedAttachment = {
    id: 'a1',
    url: '/api/v1/documents/d1/attachments/a1',
    fileName: 'shot.png',
    contentType: 'image/png',
  };

  it('hides the attach affordance when no uploader is provided', () => {
    render(<Host />);
    expect(screen.queryByRole('button', { name: 'Attach file' })).not.toBeInTheDocument();
  });

  it('runs a picked image through the placeholder flow into Markdown image syntax', async () => {
    const upload = vi.fn().mockResolvedValue(uploaded);
    render(<Host onUploadAttachment={upload} />);

    const file = new File(['bytes'], 'shot.png', { type: 'image/png' });
    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });

    // The placeholder lands immediately, GitHub-style…
    expect(textarea().value).toContain('![Uploading shot.png…]()');
    // …and resolves into the real reference.
    await waitFor(() =>
      expect(textarea().value).toBe('![shot.png](/api/v1/documents/d1/attachments/a1)'),
    );
    expect(upload).toHaveBeenCalledWith(file);
  });

  it('runs a picked document through the placeholder flow into Markdown link syntax', async () => {
    const upload = vi.fn().mockResolvedValue({
      id: 'a2',
      url: '/api/v1/documents/d1/attachments/a2',
      fileName: 'report.pdf',
      contentType: 'application/pdf',
    });
    render(<Host onUploadAttachment={upload} />);

    const file = new File(['bytes'], 'report.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });

    // A non-image placeholder is a plain link, not image syntax.
    expect(textarea().value).toContain('[Uploading report.pdf…]()');
    expect(textarea().value).not.toContain('![Uploading');
    await waitFor(() =>
      expect(textarea().value).toBe('[report.pdf](/api/v1/documents/d1/attachments/a2)'),
    );
  });

  it('rolls the placeholder back when the upload fails', async () => {
    const upload = vi.fn().mockRejectedValue(new Error('rejected'));
    render(<Host onUploadAttachment={upload} />);

    const file = new File(['bytes'], 'nope.png', { type: 'image/png' });
    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });

    await waitFor(() => expect(textarea().value).toBe(''));
  });

  it('accepts files dropped onto the composer', async () => {
    const upload = vi.fn().mockResolvedValue(uploaded);
    render(<Host onUploadAttachment={upload} />);

    const file = new File(['bytes'], 'shot.png', { type: 'image/png' });
    fireEvent.drop(screen.getByTestId('markdown-composer'), {
      dataTransfer: { types: ['Files'], files: [file] },
    });

    await waitFor(() => expect(upload).toHaveBeenCalledWith(file));
  });

  it('uploads files pasted from the clipboard', async () => {
    const upload = vi.fn().mockResolvedValue(uploaded);
    render(<Host onUploadAttachment={upload} />);

    const file = new File(['bytes'], 'paste.png', { type: 'image/png' });
    fireEvent.paste(textarea(), { clipboardData: { files: [file] } });

    await waitFor(() => expect(upload).toHaveBeenCalledWith(file));
  });

  it('offers the full-screen toggle only when the host provides one (issue #403)', () => {
    render(<Host />);
    expect(screen.queryByRole('button', { name: 'Full screen' })).not.toBeInTheDocument();
  });

  it('fires the full-screen toggle', () => {
    const onToggleFullscreen = vi.fn();
    render(<Host onToggleFullscreen={onToggleFullscreen} />);

    fireEvent.click(screen.getByRole('button', { name: 'Full screen' }));

    expect(onToggleFullscreen).toHaveBeenCalledTimes(1);
  });
});
