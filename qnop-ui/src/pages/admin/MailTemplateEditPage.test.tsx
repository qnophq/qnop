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
import { AxiosError, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MailTemplateResponse } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { MailTemplateEditPage } from './MailTemplateEditPage';

const { updateMutate, resetMutate, navigateMock, sendTemplateTestEmail } = vi.hoisted(() => ({
  updateMutate: vi.fn(),
  resetMutate: vi.fn(),
  navigateMock: vi.fn(),
  sendTemplateTestEmail: vi.fn(),
}));

vi.mock('../../api/config', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/config')>()),
  adminEmailApi: { sendTemplateTestEmail },
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
  variants: [
    {
      locale: 'en',
      source: 'DATABASE',
      shipped: true,
      instanceDefault: true,
      subject: 'Reset your {{siteName}} password',
    },
    { locale: 'de', source: 'DEFAULT', shipped: true, instanceDefault: false, subject: 'Setze' },
  ],
};

/** The template the page loads; a test may swap it before rendering. */
let current: MailTemplateResponse = TEMPLATE;
/** A load failure to simulate instead, and the route parameters. */
let loadError: unknown = null;
let params: { key: string; locale?: string } = { key: 'auth.password_reset' };

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useMailTemplate: () =>
    loadError
      ? { data: undefined, error: loadError, isLoading: false, isError: true, isFetching: false }
      : { data: current, error: null, isLoading: false, isError: false, isFetching: false },
  useUpdateMailTemplate: () => ({ mutateAsync: updateMutate, isPending: false }),
  useResetMailTemplate: () => ({ mutateAsync: resetMutate, isPending: false }),
  useSendTestEmail: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock('../../api/hooks/useMailTemplatePreview', () => ({
  useMailTemplatePreview: () => ({ status: 'idle', data: null, error: null, refresh: vi.fn() }),
}));

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useParams: () => params,
  useNavigate: () => navigateMock,
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
  current = TEMPLATE;
  loadError = null;
  params = { key: 'auth.password_reset' };
  navigateMock.mockReset();
  updateMutate.mockReset().mockResolvedValue(TEMPLATE);
  resetMutate.mockReset().mockResolvedValue(undefined);
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

    await waitFor(() =>
      expect(resetMutate).toHaveBeenCalledWith({ key: 'auth.password_reset', locale: 'en' }),
    );
  });

  it('renders the live preview pane beside the editor', () => {
    renderPage();

    expect(screen.getByText('Live preview')).toBeTruthy();
  });

  it('offers every language, marking the one being edited', () => {
    renderPage();

    const nav = screen.getByRole('navigation', { name: 'Template language' });
    const english = within(nav).getByRole('link', {
      name: 'English (default language), Customised',
    });
    expect(english.getAttribute('aria-current')).toBe('page');
    const german = within(nav).getByRole('link', { name: 'German, Built-in' });
    expect(german.getAttribute('href')).toBe('/admin/email/templates/auth.password_reset/de');
  });

  it('asks before a language switch discards unsaved changes', () => {
    renderPage();

    fireEvent.change(screen.getByLabelText(/Subject/), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('link', { name: 'German, Built-in' }));

    expect(screen.getByText('Discard unsaved changes?')).toBeTruthy();
    expect(navigateMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Discard and switch' }));
    expect(navigateMock).toHaveBeenCalledWith('/admin/email/templates/auth.password_reset/de');
  });

  it('warns when the language ships no version and English stands in', () => {
    current = {
      ...TEMPLATE,
      locale: 'it',
      source: 'DEFAULT',
      variants: [
        { locale: 'en', source: 'DEFAULT', shipped: true, instanceDefault: false, subject: 'S' },
        { locale: 'it', source: 'DEFAULT', shipped: false, instanceDefault: true, subject: 'S' },
      ],
    };
    renderPage();

    expect(screen.getByText(/qnop ships no Italian version of this template/)).toBeTruthy();
  });

  it('switches language without asking when nothing is unsaved', () => {
    renderPage();

    fireEvent.click(screen.getByRole('link', { name: 'German, Built-in' }));

    expect(screen.queryByText('Discard unsaved changes?')).toBeNull();
  });

  it('keeps the draft when a language switch is cancelled', () => {
    renderPage();

    fireEvent.change(screen.getByLabelText(/Subject/), { target: { value: 'Changed' } });
    fireEvent.click(screen.getByRole('link', { name: 'German, Built-in' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(navigateMock).not.toHaveBeenCalled();
    expect((screen.getByLabelText(/Subject/) as HTMLInputElement).value).toBe('Changed');
  });

  it('names the default language for screen readers', () => {
    renderPage();

    expect(
      screen.getByRole('link', { name: 'English (default language), Customised' }),
    ).toBeTruthy();
  });

  it('sends the test mail in the edited language', async () => {
    current = { ...TEMPLATE, locale: 'de' };
    sendTemplateTestEmail.mockResolvedValue({ data: { status: 'SENT', detail: 'ok' } });
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Send test email' }));
    fireEvent.change(screen.getByRole('textbox', { name: /Recipient/i }), {
      target: { value: 'ada@qnop.test' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await waitFor(() =>
      expect(sendTemplateTestEmail).toHaveBeenCalledWith(
        expect.objectContaining({
          templateTestEmailRequest: expect.objectContaining({ locale: 'de' }),
        }),
      ),
    );
  });

  it('points a refused language back to the default language', () => {
    params = { key: 'auth.password_reset', locale: 'it' };
    loadError = new AxiosError('Bad Request', '400', undefined, undefined, {
      status: 400,
    } as AxiosResponse);
    renderPage();

    expect(screen.getByText(/not offered in this language/)).toBeTruthy();
    expect(
      screen.getByRole('link', { name: 'Open the default language' }).getAttribute('href'),
    ).toBe('/admin/email/templates/auth.password_reset');
  });
});
