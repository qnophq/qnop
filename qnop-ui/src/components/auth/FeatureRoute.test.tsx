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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter, Route, Routes } from 'react-router';
import { FeatureRoute } from './FeatureRoute';
import { useConfig } from '../../api/hooks/useConfig';
import { buildTheme } from '../../theme/theme';

// The guard is what is under test, not the query wiring — mocking the hook is
// the only way to produce all four states it has to tell apart, the failed one
// included.
vi.mock('../../api/hooks/useConfig', () => ({ useConfig: vi.fn() }));

type ConfigState = { data?: unknown; isError?: boolean };

function renderGuard(state: ConfigState) {
  vi.mocked(useConfig).mockReturnValue(state as ReturnType<typeof useConfig>);
  // Routed rather than rendered bare: the guard's answer to a withheld
  // capability is to leave the shell for the full-page error, so the test has
  // to be able to observe where it went.
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/admin/oidc-providers']}>
        <Routes>
          <Route path="/feature-unavailable" element={<div>full-page error</div>} />
          <Route
            path="/admin/oidc-providers"
            element={
              <FeatureRoute feature="oidc">
                <div>provider settings</div>
              </FeatureRoute>
            }
          />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('FeatureRoute (#682)', () => {
  it('refuses the destination when the deployment withholds the capability', () => {
    renderGuard({ data: { features: { oidc: false } } });

    expect(screen.queryByText('provider settings')).not.toBeInTheDocument();
    // It leaves the shell entirely — the sidebar, and on the mail routes the
    // Email header and tab strip, would otherwise frame the refusal as a panel
    // that failed to load.
    expect(screen.getByText('full-page error')).toBeInTheDocument();
  });

  it('renders the page where the capability is present', () => {
    renderGuard({ data: { features: { oidc: true } } });

    expect(screen.getByText('provider settings')).toBeInTheDocument();
  });

  it('renders nothing at all while the config is still loading', () => {
    // Not the error page: flashing "unavailable" onto a deployment that has the
    // capability is worse than a moment of blank.
    renderGuard({});

    expect(screen.queryByText('provider settings')).not.toBeInTheDocument();
    expect(screen.queryByText('full-page error')).not.toBeInTheDocument();
  });

  it('shows the page when the config could not be loaded at all', () => {
    // A failed config is not evidence that a capability is missing, and the
    // endpoints refuse on their own — so this errs towards the page rather than
    // locking an administrator out of a deployment that has the feature.
    renderGuard({ isError: true });

    expect(screen.getByText('provider settings')).toBeInTheDocument();
  });
});
