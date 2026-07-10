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
import { AxiosError, type AxiosResponse } from 'axios';
import { axe } from 'vitest-axe';
import { renderWithProviders } from '../../test/renderWithProviders';
import { resetPassword } from '../../api/auth';
import { ResetPasswordPage } from './ResetPasswordPage';

vi.mock('../../api/auth', () => ({ resetPassword: vi.fn() }));
const resetPasswordFn = vi.mocked(resetPassword);

const AXE_OPTIONS = { rules: { region: { enabled: false } } };
const STRONG = 'brand-new-strong-pw';
const withToken = { initialEntries: ['/reset-password?token=reset-tok'] };

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ResetPasswordPage', () => {
  it('rejects a link with no token and points to requesting a new one', () => {
    renderWithProviders(<ResetPasswordPage />, { initialEntries: ['/reset-password'] });

    expect(screen.getByText(/this link is invalid or incomplete/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save password' })).not.toBeInTheDocument();
  });

  it('warns when the two passwords do not match', () => {
    renderWithProviders(<ResetPasswordPage />, withToken);

    fireEvent.change(screen.getByLabelText(/^New password/), { target: { value: STRONG } });
    fireEvent.change(screen.getByLabelText(/^Confirm password/), {
      target: { value: 'different' },
    });

    expect(screen.getByText(/passwords don't match/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save password' })).toBeDisabled();
  });

  it('submits the token and new password, then confirms success', async () => {
    resetPasswordFn.mockResolvedValue();
    renderWithProviders(<ResetPasswordPage />, withToken);

    fireEvent.change(screen.getByLabelText(/^New password/), { target: { value: STRONG } });
    fireEvent.change(screen.getByLabelText(/^Confirm password/), { target: { value: STRONG } });
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));

    expect(await screen.findByText(/your password has been changed/i)).toBeInTheDocument();
    expect(resetPasswordFn).toHaveBeenCalledWith('reset-tok', STRONG);
  });

  it('surfaces the link-expired message on a 400', async () => {
    resetPasswordFn.mockRejectedValue(
      new AxiosError('x', 'ERR', undefined, undefined, {
        status: 400,
        data: { code: 'INVALID_TOKEN' },
      } as AxiosResponse),
    );
    renderWithProviders(<ResetPasswordPage />, withToken);

    fireEvent.change(screen.getByLabelText(/^New password/), { target: { value: STRONG } });
    fireEvent.change(screen.getByLabelText(/^Confirm password/), { target: { value: STRONG } });
    fireEvent.click(screen.getByRole('button', { name: 'Save password' }));

    expect(
      await screen.findByText('The request is invalid or the link has expired.'),
    ).toBeInTheDocument();
  });

  it('has no accessibility violations', async () => {
    const { container } = renderWithProviders(<ResetPasswordPage />, withToken);

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });
});
