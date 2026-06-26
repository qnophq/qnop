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

import { forwardRef, useImperativeHandle, type ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MailTemplateResponse } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { MailTemplateEditPage } from './MailTemplateEditPage';

const { updateMutate, resetMutate, previewMutate } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  resetMutate: vi.fn(),
  previewMutate: vi.fn(),
}));

const TEMPLATE: MailTemplateResponse = {
  key: 'auth.password_reset',
  friendlyName: 'Password reset',
  locale: 'en',
  subject: 'Reset your {{siteName}} password',
  bodyPlain: 'Hi {{recipientName}}, open {{actionUrl}}',
  source: 'DATABASE',
  placeholders: ['actionUrl', 'recipientName', 'siteName'],
  defaultSubject: 'Reset your {{siteName}} password',
  defaultBodyPlain: 'Default plain',
  defaultBodyHtml: '<p>Default {{siteName}}</p>',
  updatedAt: new Date().toISOString(),
  updatedByName: 'Ada Admin',
};

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useMailTemplate: () => ({ data: TEMPLATE, isLoading: false, isError: false, isFetching: false }),
  useUpdateMailTemplate: () => ({ mutateAsync: updateMutate, isPending: false }),
  useResetMailTemplate: () => ({ mutateAsync: resetMutate, isPending: false }),
  usePreviewMailTemplate: () => ({ mutateAsync: previewMutate, isPending: false }),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useParams: () => ({ key: 'auth.password_reset' }),
}));

// Mock the CodeMirror editor — its DOM measurement doesn't run under jsdom.
vi.mock('../../components/admin/mail/mustache/MustacheCodeEditor', () => ({
  MustacheCodeEditor: forwardRef<
    { insertAtCursor: (t: string) => void; focus: () => void },
    {
      value: string;
      onChange: (v: string) => void;
      onFocus?: () => void;
      language?: 'plain' | 'html';
    }
  >(function MockEditor(props, ref) {
    useImperativeHandle(ref, () => ({ insertAtCursor: vi.fn(), focus: vi.fn() }));
    return (
      <textarea
        aria-label={props.language === 'html' ? 'HTML body' : 'Plain body'}
        value={props.value}
        onFocus={props.onFocus}
        onChange={(e) => props.onChange(e.target.value)}
      />
    );
  }),
}));

beforeEach(() => {
  updateMutate.mockReset().mockResolvedValue(TEMPLATE);
  resetMutate.mockReset().mockResolvedValue(undefined);
  previewMutate.mockReset().mockResolvedValue({ subject: 's', bodyPlain: 'p', sampleVars: {} });
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>{children}</MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<MailTemplateEditPage />, { wrapper });

describe('MailTemplateEditPage', () => {
  it('saves the edited subject and plain body', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText(/Subject/), {
      target: { value: 'New subject {{siteName}}' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({
        key: 'auth.password_reset',
        request: {
          locale: 'en',
          subject: 'New subject {{siteName}}',
          bodyPlain: 'Hi {{recipientName}}, open {{actionUrl}}',
          bodyHtml: undefined,
        },
      }),
    );
  });

  it('inserts a placeholder chip at the subject caret', () => {
    renderPage();

    const subject = screen.getByLabelText(/Subject/) as HTMLInputElement;
    fireEvent.focus(subject);
    fireEvent.click(screen.getByText('{{recipientName}}'));

    expect(subject.value).toContain('{{recipientName}}');
  });

  it('reveals the HTML editor when the alternative is enabled', () => {
    renderPage();

    expect(screen.queryByLabelText('HTML body')).toBeNull();
    fireEvent.click(screen.getByLabelText('Add HTML alternative'));
    expect(screen.getByLabelText('HTML body')).toBeTruthy();
  });

  it('resets to default after confirmation', async () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Reset to default' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(resetMutate).toHaveBeenCalledWith('auth.password_reset'));
  });
});
