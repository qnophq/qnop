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

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ProviderCallbackInstructions } from './ProviderCallbackInstructions';

describe('ProviderCallbackInstructions', () => {
  it('renders the Spring callback URL built from the provider id', () => {
    render(<ProviderCallbackInstructions providerId="abc-123" providerType="GOOGLE" />);
    expect(
      screen.getByText(`${window.location.origin}/login/oauth2/code/abc-123`),
    ).toBeInTheDocument();
  });

  it('shows the provider-specific field label and a console link for known providers', () => {
    render(<ProviderCallbackInstructions providerId="abc-123" providerType="GITHUB" />);
    expect(screen.getByText('Authorization callback URL')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /github developer settings/i })).toHaveAttribute(
      'href',
      'https://github.com/settings/developers',
    );
  });

  it('stays generic (no console link) for a plain OIDC provider', () => {
    render(<ProviderCallbackInstructions providerId="abc-123" providerType="OIDC" />);
    expect(screen.getByText('Valid Redirect URIs')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /open .* console|developer settings/i })).toBeNull();
  });
});
