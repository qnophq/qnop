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
import { fireEvent, screen } from '@testing-library/react';
import type { ConfigurationResponse } from '../../api/generated';
import { renderWithProviders } from '../../test/renderWithProviders';
import { ConfigurationPage } from './ConfigurationPage';

const mockHook = vi.fn();
vi.mock('../../api/hooks/useAdminConfiguration', () => ({
  useAdminConfiguration: () => mockHook(),
}));

const RESPONSE: ConfigurationResponse = {
  groups: [
    {
      key: 'auth',
      entries: [
        {
          path: 'qnop.auth.jwt-secret',
          envVar: 'QNOP_AUTH_JWT_SECRET',
          valueType: 'SECRET',
          configured: true,
        },
        {
          path: 'qnop.auth.access-token-ttl',
          envVar: 'QNOP_AUTH_ACCESS_TOKEN_TTL',
          valueType: 'DURATION',
          value: 'PT15M',
        },
        {
          path: 'qnop.auth.cookie-secure',
          envVar: 'QNOP_AUTH_COOKIE_SECURE',
          valueType: 'BOOLEAN',
          value: 'true',
        },
      ],
    },
    {
      key: 'cors',
      entries: [
        {
          path: 'qnop.cors.allowed-origins',
          envVar: 'QNOP_CORS_ALLOWED_ORIGINS',
          valueType: 'LIST',
          value: 'http://a, http://b',
        },
        {
          path: 'qnop.auth.rate-limit.trusted-proxy-cidrs',
          envVar: 'QNOP_AUTH_RATE_LIMIT_TRUSTED_PROXY_CIDRS',
          valueType: 'LIST',
          value: '',
        },
      ],
    },
    {
      key: 's3',
      entries: [
        {
          path: 'qnop.s3.secret-key',
          envVar: 'QNOP_S3_SECRET_KEY',
          valueType: 'SECRET',
          configured: false,
        },
        { path: 'qnop.s3.endpoint', envVar: 'QNOP_S3_ENDPOINT', valueType: 'UNSET' },
        {
          path: 'qnop.s3.region',
          envVar: 'QNOP_S3_REGION',
          valueType: 'STRING',
          value: 'us-east-1',
        },
        {
          path: 'qnop.s3.path-style-access',
          envVar: 'QNOP_S3_PATH_STYLE_ACCESS',
          valueType: 'BOOLEAN',
          value: 'false',
        },
      ],
    },
  ],
};

beforeEach(() => {
  mockHook.mockReset();
});

describe('ConfigurationPage', () => {
  it('renders each namespace group and redacted secret chips (never a secret value)', () => {
    mockHook.mockReturnValue({ data: RESPONSE, isLoading: false, isError: false });
    renderWithProviders(<ConfigurationPage />);

    expect(screen.getByText('qnop.auth')).toBeInTheDocument();
    expect(screen.getByText('qnop.cors')).toBeInTheDocument();
    expect(screen.getByText('qnop.s3')).toBeInTheDocument();

    // A configured secret and an unconfigured secret each render only as a state chip.
    expect(screen.getByText('Configured')).toBeInTheDocument();
    expect(screen.getByText('Not configured')).toBeInTheDocument();

    // Env var hints and value types render.
    expect(screen.getByText('QNOP_AUTH_JWT_SECRET')).toBeInTheDocument();
    expect(screen.getByText('PT15M')).toBeInTheDocument();
    expect(screen.getByText('not set')).toBeInTheDocument();
    expect(screen.getByText('us-east-1')).toBeInTheDocument();
    // Booleans render as chips (both true and false); an empty list renders as "none".
    expect(screen.getByText('true')).toBeInTheDocument();
    expect(screen.getByText('false')).toBeInTheDocument();
    expect(screen.getByText('none')).toBeInTheDocument();
  });

  it('renders a loading skeleton while the query is in flight', () => {
    mockHook.mockReturnValue({ data: undefined, isLoading: true, isError: false });
    const { container } = renderWithProviders(<ConfigurationPage />);
    expect(container.querySelector('.MuiSkeleton-root')).toBeInTheDocument();
    expect(screen.queryByText('qnop.auth')).not.toBeInTheDocument();
  });

  it('filters rows by property path or env var, dropping empty groups', () => {
    mockHook.mockReturnValue({ data: RESPONSE, isLoading: false, isError: false });
    renderWithProviders(<ConfigurationPage />);

    fireEvent.change(screen.getByPlaceholderText(/filter by property path/i), {
      target: { value: 'region' },
    });

    expect(screen.getByText('qnop.s3')).toBeInTheDocument();
    expect(screen.getByText('us-east-1')).toBeInTheDocument();
    expect(screen.queryByText('qnop.auth')).not.toBeInTheDocument();
    expect(screen.queryByText('qnop.cors')).not.toBeInTheDocument();
  });

  it('shows a no-match message when the filter matches nothing', () => {
    mockHook.mockReturnValue({ data: RESPONSE, isLoading: false, isError: false });
    renderWithProviders(<ConfigurationPage />);

    fireEvent.change(screen.getByPlaceholderText(/filter by property path/i), {
      target: { value: 'nonexistent-xyz' },
    });
    expect(screen.getByText(/no settings match/i)).toBeInTheDocument();
  });

  it('renders an error state when the query fails', () => {
    mockHook.mockReturnValue({ data: undefined, isLoading: false, isError: true });
    renderWithProviders(<ConfigurationPage />);
    expect(screen.getByText(/could not load the server configuration/i)).toBeInTheDocument();
  });
});
