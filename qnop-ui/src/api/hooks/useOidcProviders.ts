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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  OidcDiscoveryResponse,
  OidcProviderCreateRequest,
  OidcProviderListResponse,
  OidcProviderUpdateRequest,
} from '../generated';
import { adminOidcProvidersApi } from '../config';

export const oidcProviderKeys = {
  all: ['admin', 'oidc-providers'] as const,
};

/** All configured OIDC/OAuth2 identity providers. */
export function useOidcProviders() {
  return useQuery<OidcProviderListResponse>({
    queryKey: oidcProviderKeys.all,
    queryFn: async () => {
      const response = await adminOidcProvidersApi.listOidcProviders();
      return response.data;
    },
  });
}

/** Creates a provider (starts disabled until verified), then refreshes the list. */
export function useCreateOidcProvider() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: OidcProviderCreateRequest) => {
      const response = await adminOidcProvidersApi.createOidcProvider({
        oidcProviderCreateRequest: request,
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: oidcProviderKeys.all }),
  });
}

/** Updates a provider (blank client secret keeps the stored one), then refreshes. */
export function useUpdateOidcProvider() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { id: string; request: OidcProviderUpdateRequest }) => {
      const response = await adminOidcProvidersApi.updateOidcProvider({
        providerId: vars.id,
        oidcProviderUpdateRequest: vars.request,
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: oidcProviderKeys.all }),
  });
}

/** Deletes a provider, then refreshes the list. */
export function useDeleteOidcProvider() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await adminOidcProvidersApi.deleteOidcProvider({ providerId: id });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: oidcProviderKeys.all }),
  });
}

/**
 * Probes an issuer's discovery document. The endpoint never fails the request —
 * the outcome is reported in the response's {@code success} flag — so callers
 * inspect the returned object rather than catching.
 */
export function useDiscoverOidcEndpoints() {
  return useMutation({
    mutationFn: async (issuerUri: string): Promise<OidcDiscoveryResponse> => {
      const response = await adminOidcProvidersApi.discoverOidcEndpoints({
        oidcDiscoveryRequest: { issuerUri },
      });
      return response.data;
    },
  });
}
