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
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ServerConfigBranding, ServerConfigResponse } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { BrandingPage } from './BrandingPage';

const BRANDING: ServerConfigBranding = {
  logoLight: { source: 'DEFAULT', url: '/branding/logo-light.svg?v=aaa' },
  logoDark: { source: 'CUSTOM', url: '/branding/logo-dark.svg?v=bbb' },
  logomark: { source: 'DEFAULT', url: '/branding/logomark.svg?v=ccc' },
};

const CONFIG: Partial<ServerConfigResponse> = { branding: BRANDING };

// Mutable state the useConfig mock reads, so each test can drive a branch.
const configState = vi.hoisted(() => ({
  data: undefined as Partial<ServerConfigResponse> | undefined,
  isLoading: false,
  isError: false,
}));

vi.mock('../../api/hooks/useConfig', () => ({
  useConfig: () => configState,
}));

// Stub the child card to its user-visible label so we assert the loaded slots
// without pulling in the real component's upload/cropper internals.
vi.mock('../../components/admin/branding/BrandingSlotCard', () => ({
  BrandingSlotCard: ({ label }: { label: string }) => <div>{label}</div>,
}));

beforeEach(() => {
  configState.data = CONFIG;
  configState.isLoading = false;
  configState.isError = false;
});

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const renderPage = () => render(<BrandingPage />, { wrapper });

const SLOT_LABELS = ['Logo (light)', 'Logo (dark)', 'Logomark'];

describe('BrandingPage', () => {
  it('shows skeletons and no slot cards while the config is loading', () => {
    configState.isLoading = true;
    configState.data = undefined;

    const { container } = renderPage();

    expect(container.querySelectorAll('.MuiSkeleton-root').length).toBe(3);
    for (const label of SLOT_LABELS) {
      expect(screen.queryByText(label)).toBeNull();
    }
  });

  it('surfaces an error alert when the config could not be loaded', () => {
    configState.isError = true;
    configState.data = undefined;

    renderPage();

    expect(screen.getByText('The branding configuration could not be loaded.')).toBeTruthy();
    for (const label of SLOT_LABELS) {
      expect(screen.queryByText(label)).toBeNull();
    }
  });

  it('renders a card for each of the three branding slots once loaded', () => {
    renderPage();

    for (const label of SLOT_LABELS) {
      expect(screen.getByText(label)).toBeTruthy();
    }
  });
});
