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

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OidcButtons } from './OidcButtons';
import { useConfig } from '../../api/hooks/useConfig';

vi.mock('../../api/hooks/useConfig', () => ({ useConfig: vi.fn() }));

function mockProviders(providers: unknown[]) {
  vi.mocked(useConfig).mockReturnValue({
    data: { auth: { oidcProviders: providers } },
  } as never);
}

const GOOGLE = {
  id: '1',
  name: 'Google',
  loginUrl: '/oauth2/authorization/1',
  iconKind: 'google',
  accountPickerLoginUrl: '/oauth2/authorization/1?prompt=select_account',
};

const GITHUB = {
  id: '2',
  name: 'GitHub',
  loginUrl: '/oauth2/authorization/2',
  iconKind: 'github',
  accountSwitchHintUrl: 'https://github.com/logout',
};

describe('OidcButtons', () => {
  it('renders nothing when no providers are enabled', () => {
    mockProviders([]);
    const { container } = render(<OidcButtons />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the primary button as an anchor pointing at the login URL', () => {
    mockProviders([GOOGLE]);
    render(<OidcButtons />);
    const link = screen.getByRole('link', { name: /sign in with google/i });
    expect(link).toHaveAttribute('href', '/oauth2/authorization/1');
  });

  it('renders a "Use a different account" link for a prompt-capable provider', () => {
    mockProviders([GOOGLE]);
    render(<OidcButtons />);
    const switcher = screen.getByRole('link', { name: /different google account/i });
    expect(switcher).toHaveAttribute('href', '/oauth2/authorization/1?prompt=select_account');
  });

  it('renders a sign-out hint with the host for a provider without a picker (GitHub)', () => {
    mockProviders([GITHUB]);
    render(<OidcButtons />);
    expect(screen.getByText(/to switch accounts/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /different github account/i })).toBeNull();
    expect(screen.getByRole('link', { name: 'github.com' })).toHaveAttribute(
      'href',
      'https://github.com/logout',
    );
  });

  it('renders one button per enabled provider', () => {
    mockProviders([GOOGLE, GITHUB]);
    render(<OidcButtons />);
    expect(screen.getByRole('link', { name: /sign in with google/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /sign in with github/i })).toBeInTheDocument();
  });
});
