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

import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MailTemplateListResponse } from '../generated';
import {
  mailTemplateKeys,
  useMailTemplates,
  usePreviewMailTemplate,
  useResetMailTemplate,
  useSendTestEmail,
  useUpdateMailTemplate,
} from './useMailTemplates';
import { adminEmailApi } from '../config';

const EMPTY: MailTemplateListResponse = { templates: [] };

vi.mock('../config', () => ({
  adminEmailApi: {
    listMailTemplates: vi.fn(),
    updateMailTemplate: vi.fn(),
    resetMailTemplate: vi.fn(),
    previewMailTemplate: vi.fn(),
    sendTestEmail: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('mailTemplateKeys', () => {
  it('namespaces the templates query', () => {
    expect(mailTemplateKeys.all).toEqual(['admin', 'mail-templates']);
  });
});

describe('useMailTemplates', () => {
  it('fetches and returns the template list', async () => {
    vi.mocked(adminEmailApi.listMailTemplates).mockResolvedValue({ data: EMPTY } as Awaited<
      ReturnType<typeof adminEmailApi.listMailTemplates>
    >);

    const { result } = renderHook(() => useMailTemplates(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminEmailApi.listMailTemplates).toHaveBeenCalled();
    expect(result.current.data?.templates).toEqual([]);
  });
});

describe('useUpdateMailTemplate', () => {
  it('forwards the key and request', async () => {
    vi.mocked(adminEmailApi.updateMailTemplate).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminEmailApi.updateMailTemplate>
    >);

    const { result } = renderHook(() => useUpdateMailTemplate(), { wrapper });
    await result.current.mutateAsync({
      key: 'auth.password_reset',
      request: { locale: 'en', subject: 'Hi', bodyPlain: 'Body' },
    });

    expect(adminEmailApi.updateMailTemplate).toHaveBeenCalledWith({
      key: 'auth.password_reset',
      updateMailTemplateRequest: { locale: 'en', subject: 'Hi', bodyPlain: 'Body' },
    });
  });
});

describe('useResetMailTemplate', () => {
  it('resets by key', async () => {
    vi.mocked(adminEmailApi.resetMailTemplate).mockResolvedValue({ data: undefined } as Awaited<
      ReturnType<typeof adminEmailApi.resetMailTemplate>
    >);

    const { result } = renderHook(() => useResetMailTemplate(), { wrapper });
    await result.current.mutateAsync('auth.password_reset');

    expect(adminEmailApi.resetMailTemplate).toHaveBeenCalledWith({ key: 'auth.password_reset' });
  });
});

describe('usePreviewMailTemplate', () => {
  it('wraps the key and locale', async () => {
    vi.mocked(adminEmailApi.previewMailTemplate).mockResolvedValue({
      data: { subject: 'S', bodyPlain: 'B' },
    } as Awaited<ReturnType<typeof adminEmailApi.previewMailTemplate>>);

    const { result } = renderHook(() => usePreviewMailTemplate(), { wrapper });
    const out = await result.current.mutateAsync({ key: 'k', locale: 'de' });

    expect(adminEmailApi.previewMailTemplate).toHaveBeenCalledWith({
      key: 'k',
      previewMailTemplateRequest: { locale: 'de' },
    });
    expect(out.subject).toBe('S');
  });
});

describe('useSendTestEmail', () => {
  it('wraps the recipient and returns the outcome', async () => {
    vi.mocked(adminEmailApi.sendTestEmail).mockResolvedValue({
      data: { status: 'SENT', detail: 'ok' },
    } as Awaited<ReturnType<typeof adminEmailApi.sendTestEmail>>);

    const { result } = renderHook(() => useSendTestEmail(), { wrapper });
    const out = await result.current.mutateAsync('a@example.com');

    expect(adminEmailApi.sendTestEmail).toHaveBeenCalledWith({
      sendTestEmailRequest: { recipient: 'a@example.com' },
    });
    expect(out.status).toBe('SENT');
  });
});
