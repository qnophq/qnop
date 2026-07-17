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
import type { AuditEventListResponse } from '../generated';
import { auditKeys, useAuditLog } from './useAuditLog';
import { auditApi } from '../config';

const EMPTY_PAGE: AuditEventListResponse = { items: [], total: 0, page: 0, size: 20 };

vi.mock('../config', () => ({
  auditApi: {
    listAuditEvents: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('auditKeys', () => {
  it('namespaces list keys by params', () => {
    const params = { eventType: 'workflow.transition', page: 1, size: 20 };
    expect(auditKeys.list(params)).toEqual(['audit', 'list', params]);
  });
});

describe('useAuditLog', () => {
  it('passes all filters and pagination through and returns the page', async () => {
    vi.mocked(auditApi.listAuditEvents).mockResolvedValue({ data: EMPTY_PAGE } as Awaited<
      ReturnType<typeof auditApi.listAuditEvents>
    >);

    const { result } = renderHook(
      () =>
        useAuditLog({
          eventType: 'annotation.resolved',
          actorId: 'actor-1',
          documentId: 'doc-1',
          from: '2026-01-01T00:00:00.000Z',
          to: '2026-12-31T00:00:00.000Z',
          page: 2,
          size: 50,
        }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(auditApi.listAuditEvents).toHaveBeenCalledWith({
      eventType: 'annotation.resolved',
      actorId: 'actor-1',
      documentId: 'doc-1',
      from: '2026-01-01T00:00:00.000Z',
      to: '2026-12-31T00:00:00.000Z',
      page: 2,
      size: 50,
    });
    expect(result.current.data?.total).toBe(0);
  });

  it('normalises empty-string filters to undefined', async () => {
    vi.mocked(auditApi.listAuditEvents).mockResolvedValue({ data: EMPTY_PAGE } as Awaited<
      ReturnType<typeof auditApi.listAuditEvents>
    >);

    const { result } = renderHook(
      () => useAuditLog({ eventType: '', from: '', to: '', page: 0, size: 20 }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(auditApi.listAuditEvents).toHaveBeenCalledWith({
      eventType: undefined,
      actorId: undefined,
      documentId: undefined,
      from: undefined,
      to: undefined,
      page: 0,
      size: 20,
    });
  });
});
