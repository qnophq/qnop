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
import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useDeleteBrandingAsset, useUploadBrandingAsset } from './useBranding';
import { deleteBrandingAsset, uploadBrandingAsset } from '../branding';

vi.mock('../branding', () => ({
  uploadBrandingAsset: vi.fn(),
  deleteBrandingAsset: vi.fn(),
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useUploadBrandingAsset', () => {
  it('forwards the slot and file to the uploader', async () => {
    vi.mocked(uploadBrandingAsset).mockResolvedValue({
      contentType: 'image/png',
      sha256: 'abc',
      sizeBytes: 10,
    });
    const file = new File(['x'], 'logo.png', { type: 'image/png' });

    const { result } = renderHook(() => useUploadBrandingAsset(), { wrapper });
    await result.current.mutateAsync({ slot: 'logo-light', file, filename: 'logo.png' });

    expect(uploadBrandingAsset).toHaveBeenCalledWith('logo-light', file, 'logo.png');
  });
});

describe('useDeleteBrandingAsset', () => {
  it('deletes the given slot', async () => {
    vi.mocked(deleteBrandingAsset).mockResolvedValue(undefined);

    const { result } = renderHook(() => useDeleteBrandingAsset(), { wrapper });
    await result.current.mutateAsync('logomark');

    expect(deleteBrandingAsset).toHaveBeenCalledWith('logomark');
  });
});
