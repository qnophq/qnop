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
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/renderWithProviders';
import { verifyEmail } from '../../api/auth';
import { VerifyEmailPage } from './VerifyEmailPage';

vi.mock('../../api/auth', () => ({ verifyEmail: vi.fn() }));
const verifyEmailFn = vi.mocked(verifyEmail);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('VerifyEmailPage', () => {
  it('verifies the token from the link and confirms success', async () => {
    verifyEmailFn.mockResolvedValue();
    renderWithProviders(<VerifyEmailPage />, {
      initialEntries: ['/verify-email?token=good-token'],
    });

    expect(await screen.findByText(/your email address is confirmed/i)).toBeInTheDocument();
    expect(verifyEmailFn).toHaveBeenCalledWith('good-token');
  });

  it('shows an error when the token is rejected', async () => {
    verifyEmailFn.mockRejectedValue(new Error('invalid'));
    renderWithProviders(<VerifyEmailPage />, { initialEntries: ['/verify-email?token=stale'] });

    expect(await screen.findByText(/link is invalid or expired/i)).toBeInTheDocument();
  });

  it('shows an error and never calls the API when no token is present', () => {
    renderWithProviders(<VerifyEmailPage />, { initialEntries: ['/verify-email'] });

    expect(screen.getByText(/link is invalid or expired/i)).toBeInTheDocument();
    expect(verifyEmailFn).not.toHaveBeenCalled();
  });
});
