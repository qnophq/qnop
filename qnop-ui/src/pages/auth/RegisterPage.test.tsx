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
import { AxiosError, type AxiosResponse } from 'axios';
import { axe } from 'vitest-axe';
import { renderWithProviders } from '../../test/renderWithProviders';
import { register } from '../../api/auth';
import { useConfig } from '../../api/hooks/useConfig';
import { RegisterPage } from './RegisterPage';

vi.mock('../../api/auth', () => ({ register: vi.fn() }));
vi.mock('../../api/hooks/useConfig', () => ({ useConfig: vi.fn() }));

const registerFn = vi.mocked(register);
const useConfigMock = vi.mocked(useConfig);

const AXE_OPTIONS = { rules: { region: { enabled: false } } };
const STRONG = 'brand-new-strong-pw';

function mockConfig(selfRegistrationEnabled: boolean, isLoading = false) {
  useConfigMock.mockReturnValue({
    data: selfRegistrationEnabled ? { auth: { selfRegistrationEnabled } } : { auth: {} },
    isLoading,
  } as unknown as ReturnType<typeof useConfig>);
}

function fillValidForm() {
  fireEvent.change(screen.getByLabelText(/full name/i), { target: { value: 'Jo Doe' } });
  fireEvent.change(screen.getByLabelText(/username/i), { target: { value: 'jo' } });
  fireEvent.change(screen.getByLabelText(/work email/i), { target: { value: 'jo@x.io' } });
  fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: STRONG } });
  fireEvent.click(screen.getByRole('checkbox'));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('RegisterPage', () => {
  it('redirects to /login when self-registration is disabled', () => {
    mockConfig(false);
    renderWithProviders(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<div>login screen</div>} />
      </Routes>,
      { initialEntries: ['/register'] },
    );

    expect(screen.getByText('login screen')).toBeInTheDocument();
  });

  it('keeps submit disabled until the terms and a strong password are provided', () => {
    mockConfig(true);
    renderWithProviders(<RegisterPage />);

    const submit = screen.getByRole('button', { name: 'Create account' });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/^Password/), { target: { value: STRONG } });
    expect(submit).toBeDisabled(); // terms still unchecked

    fireEvent.click(screen.getByRole('checkbox'));
    expect(submit).toBeEnabled();
  });

  it('registers with the entered details and shows the confirmation notice', async () => {
    mockConfig(true);
    registerFn.mockResolvedValue();
    renderWithProviders(<RegisterPage />);

    fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(/sent you a confirmation email/i)).toBeInTheDocument();
    expect(registerFn).toHaveBeenCalledWith({
      displayName: 'Jo Doe',
      username: 'jo',
      email: 'jo@x.io',
      password: STRONG,
    });
  });

  it('surfaces a generic error when registration fails', async () => {
    mockConfig(true);
    registerFn.mockRejectedValue(
      new AxiosError('x', 'ERR', undefined, undefined, {
        status: 409,
        data: { code: 'EMAIL_TAKEN' },
      } as AxiosResponse),
    );
    renderWithProviders(<RegisterPage />);

    fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(
      await screen.findByText('Registration failed. Please try again later.'),
    ).toBeInTheDocument();
  });

  it('has no accessibility violations', async () => {
    mockConfig(true);
    const { container } = renderWithProviders(<RegisterPage />);

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });
});
