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
import type { OidcProviderListResponse } from '../generated';
import {
  oidcProviderKeys,
  useCreateOidcProvider,
  useDeleteOidcProvider,
  useDiscoverOidcEndpoints,
  useOidcProviders,
  useUpdateOidcProvider,
} from './useOidcProviders';
import { adminOidcProvidersApi } from '../config';

const EMPTY: OidcProviderListResponse = { providers: [] };

vi.mock('../config', () => ({
  adminOidcProvidersApi: {
    listOidcProviders: vi.fn(),
    createOidcProvider: vi.fn(),
    updateOidcProvider: vi.fn(),
    deleteOidcProvider: vi.fn(),
    discoverOidcEndpoints: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('oidcProviderKeys', () => {
  it('namespaces the providers query', () => {
    expect(oidcProviderKeys.all).toEqual(['admin', 'oidc-providers']);
  });
});

describe('useOidcProviders', () => {
  it('fetches and returns the providers list', async () => {
    vi.mocked(adminOidcProvidersApi.listOidcProviders).mockResolvedValue({
      data: EMPTY,
    } as Awaited<ReturnType<typeof adminOidcProvidersApi.listOidcProviders>>);

    const { result } = renderHook(() => useOidcProviders(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(adminOidcProvidersApi.listOidcProviders).toHaveBeenCalled();
    expect(result.current.data?.providers).toEqual([]);
  });
});

describe('useCreateOidcProvider', () => {
  it('wraps the create request', async () => {
    vi.mocked(adminOidcProvidersApi.createOidcProvider).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminOidcProvidersApi.createOidcProvider>
    >);

    const { result } = renderHook(() => useCreateOidcProvider(), { wrapper });
    await result.current.mutateAsync({
      name: 'Google',
      providerType: 'GOOGLE',
      clientId: 'cid',
      clientSecret: 'secret',
    });

    expect(adminOidcProvidersApi.createOidcProvider).toHaveBeenCalledWith({
      oidcProviderCreateRequest: {
        name: 'Google',
        providerType: 'GOOGLE',
        clientId: 'cid',
        clientSecret: 'secret',
      },
    });
  });
});

describe('useUpdateOidcProvider', () => {
  it('forwards the id and patch', async () => {
    vi.mocked(adminOidcProvidersApi.updateOidcProvider).mockResolvedValue({ data: {} } as Awaited<
      ReturnType<typeof adminOidcProvidersApi.updateOidcProvider>
    >);

    const { result } = renderHook(() => useUpdateOidcProvider(), { wrapper });
    await result.current.mutateAsync({ id: 'p1', request: { enabled: true } });

    expect(adminOidcProvidersApi.updateOidcProvider).toHaveBeenCalledWith({
      providerId: 'p1',
      oidcProviderUpdateRequest: { enabled: true },
    });
  });
});

describe('useDeleteOidcProvider', () => {
  it('deletes by id', async () => {
    vi.mocked(adminOidcProvidersApi.deleteOidcProvider).mockResolvedValue({
      data: undefined,
    } as Awaited<ReturnType<typeof adminOidcProvidersApi.deleteOidcProvider>>);

    const { result } = renderHook(() => useDeleteOidcProvider(), { wrapper });
    await result.current.mutateAsync('p9');

    expect(adminOidcProvidersApi.deleteOidcProvider).toHaveBeenCalledWith({ providerId: 'p9' });
  });
});

describe('useDiscoverOidcEndpoints', () => {
  it('wraps the issuer URI and returns the discovery outcome', async () => {
    vi.mocked(adminOidcProvidersApi.discoverOidcEndpoints).mockResolvedValue({
      data: { success: true, authorizationUri: 'https://idp/auth' },
    } as Awaited<ReturnType<typeof adminOidcProvidersApi.discoverOidcEndpoints>>);

    const { result } = renderHook(() => useDiscoverOidcEndpoints(), { wrapper });
    const outcome = await result.current.mutateAsync('https://idp');

    expect(adminOidcProvidersApi.discoverOidcEndpoints).toHaveBeenCalledWith({
      oidcDiscoveryRequest: { issuerUri: 'https://idp' },
    });
    expect(outcome.success).toBe(true);
  });
});
