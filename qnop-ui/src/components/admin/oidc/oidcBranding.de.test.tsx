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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import type { OidcProviderDto } from '../../../api/generated';
import { BrandingSlotCard } from '../branding/BrandingSlotCard';
import { OidcProvidersTable } from './OidcProvidersTable';
import { ProviderCallbackInstructions } from './ProviderCallbackInstructions';

vi.mock('../../../api/hooks/useBranding', () => ({
  useUploadBrandingAsset: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteBrandingAsset: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light', 'de')}>{children}</ThemeProvider>
    </QueryClientProvider>
  );
}

const provider: OidcProviderDto = {
  id: 'p-1',
  name: 'Firmen-SSO',
  providerType: 'OAUTH2',
  clientId: 'qnop',
  scope: 'openid',
  enabled: false,
  createdAt: '2026-01-01T00:00:00Z',
} as OidcProviderDto;

/** The OIDC and branding admin surfaces in German (issue #836). */
describe('OIDC providers and branding in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the provider table with German headers, type label and status', () => {
    render(
      <OidcProvidersTable
        providers={[provider]}
        onEdit={vi.fn()}
        onToggleEnabled={vi.fn()}
        onDelete={vi.fn()}
      />,
      { wrapper },
    );

    expect(screen.getByRole('columnheader', { name: 'Anbieter' })).toBeInTheDocument();
    expect(screen.getByText('Generisches OAuth2')).toBeInTheDocument();
    expect(screen.getByText('Deaktiviert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Aktionen für Firmen-SSO' })).toBeInTheDocument();
  });

  it('keeps the vendor field label verbatim but translates the guidance', () => {
    render(<ProviderCallbackInstructions providerId="abc" providerType="GITHUB" />, { wrapper });

    expect(screen.getByText('Authorization callback URL')).toBeInTheDocument();
    expect(screen.getByText('Einfügen in:')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /GitHub-Entwicklereinstellungen öffnen/ }),
    ).toBeInTheDocument();
  });

  it('asks before removing a custom logo, keeping the German noun capitalised', () => {
    render(
      <BrandingSlotCard
        slot="logo-light"
        label="Logo (hell)"
        description="Wird auf hellen Hintergründen angezeigt."
        source="CUSTOM"
        url="/api/v1/branding/logo-light?v=1"
        onNotify={vi.fn()}
      />,
      { wrapper },
    );

    expect(screen.getByText('Eigenes')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Entfernen' }));
    expect(
      screen.getByText(
        'Eigenes Bild für „Logo (hell)“ entfernen? Die App nutzt dann wieder das Standardbild für „Logo (hell)“.',
      ),
    ).toBeInTheDocument();
  });
});
