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

import { useEffect, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../../theme/theme';
import {
  registerComposerMode,
  unregisterComposerMode,
  type ComposerModeSurfaceProps,
} from '../../../extensions/composerModes';
import { userSettingsApi } from '../../../api/config';
import { useAuthStore } from '../../../stores/authStore';
import { MarkdownComposer } from './MarkdownComposer';
import type { UploadedAttachment } from './useCommentAttachmentUpload';

/**
 * A test-only mode (issue #599): a plain input that speaks the surface
 * contract — value in, Markdown out, the submit chord, the handle for the
 * composer's shared affordances — and reports what it was given.
 */
const seen: { props?: ComposerModeSurfaceProps } = {};

function FakeSurface(props: ComposerModeSurfaceProps) {
  const { value, onChange, onSubmit, disabled, inputAriaLabel, handleRef } = props;
  useEffect(() => {
    seen.props = props;
    handleRef.current = {
      insertText: (text) => onChange(value + text),
      replaceText: (search, replacement) => onChange(value.replace(search, replacement)),
    };
    return () => {
      handleRef.current = null;
    };
  });
  return (
    <input
      data-testid="fake-surface"
      aria-label={`${inputAriaLabel} (fake)`}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.metaKey) onSubmit?.();
      }}
    />
  );
}

function Host({
  onSubmit,
  onUploadAttachment,
  disabled = false,
  initial = '',
  authenticated = false,
}: {
  onSubmit?: () => void;
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
  disabled?: boolean;
  initial?: string;
  authenticated?: boolean;
}) {
  const [value, setValue] = useState(initial);
  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  );
  useAuthStore.setState({ isAuthenticated: authenticated });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider theme={buildTheme('light')}>
        <MarkdownComposer
          value={value}
          onChange={setValue}
          onSubmit={onSubmit}
          onUploadAttachment={onUploadAttachment}
          disabled={disabled}
          inputAriaLabel="Test comment"
          mentionCandidates={[{ id: 'u1', name: 'Alice', slug: 'alice' }]}
        />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const fake = { id: 'fake', label: 'Fancy', Surface: FakeSurface };

afterEach(() => {
  unregisterComposerMode('fake');
  unregisterComposerMode('wysiwyg');
  seen.props = undefined;
  vi.restoreAllMocks();
  useAuthStore.setState({ isAuthenticated: false });
});

describe('MarkdownComposer — contributed modes (#599)', () => {
  it('shows no extra tab and no surface without a registered mode', () => {
    render(<Host />);
    // Alone, the tab keeps its conventional name.
    expect(screen.getByRole('button', { name: 'Write' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Markdown' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview' })).toBeInTheDocument();
    expect(screen.queryByTestId(/composer-mode-/)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Test comment').tagName).toBe('TEXTAREA');
  });

  it('adds a tab for a registered mode and swaps the textarea for its surface', () => {
    registerComposerMode(fake);
    render(<Host initial="**bold**" />);
    const tab = screen.getByTestId('composer-mode-fake');
    expect(tab).toHaveTextContent('Fancy');
    expect(tab).toHaveAttribute('aria-pressed', 'false');
    // Beside a contributed mode the first tab names what you get (#599):
    // "Write" would claim the other modes are not for writing.
    expect(screen.getByRole('button', { name: 'Markdown' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Write' })).not.toBeInTheDocument();

    fireEvent.click(tab);

    expect(tab).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByLabelText('Test comment')).not.toBeInTheDocument();
    expect(screen.getByTestId('fake-surface')).toHaveValue('**bold**');
    // The formatting toolbar belongs to the textarea, not to a surface.
    expect(screen.queryByRole('button', { name: 'Bold' })).not.toBeInTheDocument();
  });

  it('round-trips the Markdown value through the surface and back to the Markdown tab', () => {
    registerComposerMode(fake);
    render(<Host />);
    fireEvent.click(screen.getByTestId('composer-mode-fake'));
    fireEvent.change(screen.getByTestId('fake-surface'), { target: { value: '# From fake' } });

    fireEvent.click(screen.getByRole('button', { name: 'Markdown' }));

    expect(screen.getByLabelText('Test comment')).toHaveValue('# From fake');
    expect(screen.queryByTestId('fake-surface')).not.toBeInTheDocument();
  });

  it('passes the submit chord, disabled state, roster and upload callback through', () => {
    const onSubmit = vi.fn();
    const onUploadAttachment = vi.fn();
    registerComposerMode(fake);
    render(<Host onSubmit={onSubmit} onUploadAttachment={onUploadAttachment} disabled />);
    fireEvent.click(screen.getByTestId('composer-mode-fake'));

    expect(seen.props?.disabled).toBe(true);
    expect(screen.getByTestId('fake-surface')).toBeDisabled();
    expect(seen.props?.mentionCandidates).toEqual([{ id: 'u1', name: 'Alice', slug: 'alice' }]);
    expect(seen.props?.onUploadAttachment).toBe(onUploadAttachment);
    expect(seen.props?.fullscreen).toBe(false);

    fireEvent.keyDown(screen.getByTestId('fake-surface'), { key: 'Enter', metaKey: true });
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('routes the shared attach button into the surface through its handle', async () => {
    const onUploadAttachment = vi.fn(async (): Promise<UploadedAttachment> => ({
      id: 'a1',
      url: '/api/v1/attachments/a1',
      fileName: 'notes.txt',
      contentType: 'text/plain',
    }));
    registerComposerMode(fake);
    render(<Host initial="see " onUploadAttachment={onUploadAttachment} />);
    fireEvent.click(screen.getByTestId('composer-mode-fake'));

    const file = new File(['x'], 'notes.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByTestId('composer-file-input'), { target: { files: [file] } });

    await waitFor(() =>
      expect(screen.getByTestId('fake-surface')).toHaveValue(
        'see [notes.txt](/api/v1/attachments/a1)',
      ),
    );
    expect(onUploadAttachment).toHaveBeenCalledWith(file);
  });

  it('lets the active mode hide the Preview tab', () => {
    registerComposerMode({ ...fake, id: 'wysiwyg', label: 'Rich', hidesPreview: true });
    render(<Host />);
    expect(screen.getByRole('button', { name: 'Preview' })).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('composer-mode-wysiwyg'));
    expect(screen.queryByRole('button', { name: 'Preview' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Markdown' }));
    expect(screen.getByRole('button', { name: 'Preview' })).toBeInTheDocument();
  });

  it('restores the remembered mode and persists a change through the user settings', async () => {
    registerComposerMode(fake);
    const get = vi.spyOn(userSettingsApi, 'getCurrentUserSettings').mockResolvedValue({
      data: { settings: [{ key: 'composer_mode', value: 'fake', type: 'STRING' }] },
    } as never);
    const update = vi.spyOn(userSettingsApi, 'updateCurrentUserSettings').mockResolvedValue({
      data: { settings: [] },
    } as never);
    render(<Host authenticated />);

    await waitFor(() => expect(screen.getByTestId('fake-surface')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(update).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Preview' }));
    expect(update).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Markdown' }));
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith({
        userSettingsUpdateRequest: { values: { composer_mode: 'write' } },
      }),
    );
  });

  it('ignores a remembered mode no registration answers to', async () => {
    registerComposerMode(fake);
    vi.spyOn(userSettingsApi, 'getCurrentUserSettings').mockResolvedValue({
      data: { settings: [{ key: 'composer_mode', value: 'gone', type: 'STRING' }] },
    } as never);
    render(<Host authenticated />);
    await waitFor(() => expect(screen.getByLabelText('Test comment')).toBeInTheDocument());
    expect(screen.queryByTestId('fake-surface')).not.toBeInTheDocument();
  });
});
