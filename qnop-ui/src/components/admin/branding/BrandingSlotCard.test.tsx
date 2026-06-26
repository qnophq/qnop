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
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { buildTheme } from '../../../theme/theme';
import { BrandingSlotCard } from './BrandingSlotCard';

vi.mock('../../../api/hooks/useBranding', () => ({
  useUploadBrandingAsset: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteBrandingAsset: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

describe('BrandingSlotCard', () => {
  it('marks a default slot and offers only upload', () => {
    render(
      <BrandingSlotCard
        slot="logo-light"
        label="Logo (light)"
        description="Shown on light backgrounds."
        source="DEFAULT"
        url="/api/v1/branding/logo-light?v=default"
        onNotify={vi.fn()}
      />,
      { wrapper },
    );
    expect(screen.getByText('Default')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
  });

  it('marks a custom slot and offers replace + remove', () => {
    render(
      <BrandingSlotCard
        slot="logomark"
        label="Logomark"
        description="Compact mark / favicon."
        source="CUSTOM"
        url="/api/v1/branding/logomark?v=abc123"
        onNotify={vi.fn()}
      />,
      { wrapper },
    );
    expect(screen.getByText('Custom')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Replace' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeTruthy();
  });
});
