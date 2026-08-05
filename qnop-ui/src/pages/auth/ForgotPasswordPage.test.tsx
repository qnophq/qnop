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
import { useConfig } from '../../api/hooks/useConfig';
import { forgotPassword } from '../../api/auth';
import { ForgotPasswordPage } from './ForgotPasswordPage';

vi.mock('../../api/auth', () => ({ forgotPassword: vi.fn() }));
vi.mock('../../api/hooks/useConfig', () => ({ useConfig: vi.fn() }));
const forgotPasswordFn = vi.mocked(forgotPassword);

/** @param passwordResetEnabled undefined leaves the config still loading. */
function mockConfig(passwordResetEnabled?: boolean) {
  vi.mocked(useConfig).mockReturnValue({
    data: passwordResetEnabled === undefined ? undefined : { auth: { passwordResetEnabled } },
    isLoading: passwordResetEnabled === undefined,
  } as unknown as ReturnType<typeof useConfig>);
}

const AXE_OPTIONS = { rules: { region: { enabled: false } } };

beforeEach(() => {
  vi.clearAllMocks();
  // The ordinary case: a deployment that offers the reset. Cases about a
  // deployment without one say so themselves (issue #713).
  mockConfig(true);
});

describe('ForgotPasswordPage', () => {
  it('submits the email and shows the uniform acknowledgement', async () => {
    forgotPasswordFn.mockResolvedValue();
    renderWithProviders(<ForgotPasswordPage />);

    fireEvent.change(screen.getByLabelText(/work email/i), { target: { value: 'jo@x.io' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send link' }));

    expect(await screen.findByText(/reset link is on its way/i)).toBeInTheDocument();
    expect(forgotPasswordFn).toHaveBeenCalledWith('jo@x.io');
  });

  it('surfaces a rate-limit error and keeps the form on screen', async () => {
    forgotPasswordFn.mockRejectedValue(
      new AxiosError('x', 'ERR', undefined, undefined, { status: 429 } as AxiosResponse),
    );
    renderWithProviders(<ForgotPasswordPage />);

    fireEvent.change(screen.getByLabelText(/work email/i), { target: { value: 'jo@x.io' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send link' }));

    expect(
      await screen.findByText('Too many attempts. Please try again later.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send link' })).toBeInTheDocument();
  });

  it('has no accessibility violations', async () => {
    const { container } = renderWithProviders(<ForgotPasswordPage />);

    expect(await axe(container, AXE_OPTIONS)).toHaveNoViolations();
  });

  it('sends nobody to the form where the deployment offers no reset (#713)', () => {
    mockConfig(false);
    renderWithProviders(<ForgotPasswordPage />);

    // The screen is for a capability this deployment does not have, and the
    // endpoint refuses anyway — asking for an address first would be theatre.
    expect(screen.queryByLabelText(/Email/i)).not.toBeInTheDocument();
  });

  it('shows the form while the config is still loading', () => {
    // "Not known yet" is not "off": bouncing somebody off a page they are
    // entitled to would be the worse mistake.
    mockConfig(undefined);
    renderWithProviders(<ForgotPasswordPage />);

    expect(screen.getByLabelText(/Email/i)).toBeInTheDocument();
  });
});
