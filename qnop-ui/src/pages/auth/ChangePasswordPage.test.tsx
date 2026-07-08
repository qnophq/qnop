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
import { Route, Routes } from 'react-router-dom';
import { axe } from 'vitest-axe';
import { renderWithProviders } from '../../test/renderWithProviders';
import { changePassword } from '../../api/auth';
import { useAuthStore } from '../../stores/authStore';
import { ChangePasswordPage } from './ChangePasswordPage';

vi.mock('../../api/auth', () => ({ changePassword: vi.fn() }));
const changePasswordFn = vi.mocked(changePassword);

const AXE_OPTIONS = { rules: { region: { enabled: false } } };
const STRONG = 'brand-new-strong-pw';

function withRedirectProbes() {
  return (
    <Routes>
      <Route path="/change-password" element={<ChangePasswordPage />} />
      <Route path="/login" element={<div>login screen</div>} />
      <Route path="/" element={<div>home screen</div>} />
    </Routes>
  );
}

function fillForm() {
  fireEvent.change(screen.getByLabelText(/^Current password/), { target: { value: 'old-pw' } });
  fireEvent.change(screen.getByLabelText(/^New password/), { target: { value: STRONG } });
  fireEvent.change(screen.getByLabelText(/^Confirm new password/), { target: { value: STRONG } });
}

beforeEach(() => {
  vi.clearAllMocks();
  useAuthStore.setState({ accessToken: null, source: null, passwordChangeRequired: false });
});

describe('ChangePasswordPage', () => {
  it('redirects to /login without a session', () => {
    renderWithProviders(withRedirectProbes(), { initialEntries: ['/change-password'] });

    expect(screen.getByText('login screen')).toBeInTheDocument();
  });

  it('redirects an external (OIDC) account to home', () => {
    useAuthStore.setState({ accessToken: 'tok', source: 'EXTERNAL' });
    renderWithProviders(withRedirectProbes(), { initialEntries: ['/change-password'] });

    expect(screen.getByText('home screen')).toBeInTheDocument();
  });

  it('changes the password, clears the session and confirms success', async () => {
    useAuthStore.setState({ accessToken: 'tok', source: null });
    changePasswordFn.mockResolvedValue();
    renderWithProviders(<ChangePasswordPage />);

    fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByText(/your password has been changed/i)).toBeInTheDocument();
    expect(changePasswordFn).toHaveBeenCalledWith('old-pw', STRONG);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('surfaces an error when the current password is wrong', async () => {
    useAuthStore.setState({ accessToken: 'tok', source: null });
    changePasswordFn.mockRejectedValue(new Error('bad'));
    renderWithProviders(<ChangePasswordPage />);

    fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByText(/the current password is wrong/i)).toBeInTheDocument();
  });

  it('has no accessibility violations', async () => {
    useAuthStore.setState({ accessToken: 'tok', source: null });
    const { container } = renderWithProviders(<ChangePasswordPage />);

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });
});
