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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { buildTheme } from '../../../theme/theme';
import { BrandingSlotCard } from './BrandingSlotCard';

const { uploadMutate, deleteMutate } = vi.hoisted(() => ({
  uploadMutate: vi.fn(),
  deleteMutate: vi.fn(),
}));

vi.mock('../../../api/hooks/useBranding', () => ({
  useUploadBrandingAsset: () => ({ mutateAsync: uploadMutate, isPending: false }),
  useDeleteBrandingAsset: () => ({ mutateAsync: deleteMutate, isPending: false }),
}));

beforeEach(() => {
  uploadMutate.mockReset().mockResolvedValue({});
  deleteMutate.mockReset().mockResolvedValue(undefined);
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

function renderCard(props: Partial<React.ComponentProps<typeof BrandingSlotCard>> = {}) {
  const merged = {
    slot: 'logo-light' as const,
    label: 'Logo (light)',
    description: 'Shown on light backgrounds.',
    source: 'DEFAULT' as const,
    url: '/api/v1/branding/logo-light?v=default',
    onNotify: vi.fn(),
    ...props,
  };
  return { ...render(<BrandingSlotCard {...merged} />, { wrapper }), onNotify: merged.onNotify };
}

function fileInput(container: HTMLElement): HTMLInputElement {
  return container.querySelector('input[type="file"]') as HTMLInputElement;
}

describe('BrandingSlotCard', () => {
  it('marks a default slot and offers only upload', () => {
    renderCard({ source: 'DEFAULT' });
    expect(screen.getByText('Default')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
  });

  it('marks a custom slot and offers replace + remove', () => {
    renderCard({
      slot: 'logomark',
      label: 'Logomark',
      source: 'CUSTOM',
      url: '/api/v1/branding/logomark?v=abc123',
    });
    expect(screen.getByText('Custom')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeTruthy();
  });

  it('uploads an SVG directly, bypassing the cropper', async () => {
    const { container } = renderCard();
    const svg = new File(['<svg xmlns="http://www.w3.org/2000/svg"/>'], 'logo.svg', {
      type: 'image/svg+xml',
    });
    fireEvent.change(fileInput(container), { target: { files: [svg] } });

    await waitFor(() => expect(uploadMutate).toHaveBeenCalledTimes(1));
    expect(uploadMutate.mock.calls[0][0]).toMatchObject({ slot: 'logo-light', file: svg });
    // No crop dialog appears for a vector upload.
    expect(screen.queryByRole('button', { name: 'Use selection' })).toBeNull();
  });

  it('rejects an unsupported file type without uploading', () => {
    const { container, onNotify } = renderCard();
    const txt = new File(['not an image'], 'note.txt', { type: 'text/plain' });
    fireEvent.change(fileInput(container), { target: { files: [txt] } });

    expect(onNotify).toHaveBeenCalledWith(expect.stringContaining('Unsupported'), 'error');
    expect(uploadMutate).not.toHaveBeenCalled();
  });
});
