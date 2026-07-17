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

import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { useAuthStore } from '../../stores/authStore';
import { PersonLink } from './PersonLink';

const ANNA_ID = '123e4567-e89b-12d3-a456-426614174000';
const SELF_ID = '999e4567-e89b-12d3-a456-426614174999';

function renderLink(props: Parameters<typeof PersonLink>[0]) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter>
        <PersonLink {...props} />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ userId: SELF_ID });
});

describe('PersonLink profile targets (issues #454, #486)', () => {
  it('prefers the profile slug for the link target', () => {
    renderLink({ userId: ANNA_ID, slug: 'anna-krause', name: 'Anna Krause' });
    expect(screen.getByRole('link', { name: "View Anna Krause's profile" })).toHaveAttribute(
      'href',
      '/users/anna-krause',
    );
  });

  it('falls back to the id for accounts without a slug', () => {
    renderLink({ userId: ANNA_ID, name: 'Anna Krause' });
    expect(screen.getByRole('link', { name: "View Anna Krause's profile" })).toHaveAttribute(
      'href',
      `/users/${ANNA_ID}`,
    );
  });

  it('links yourself to /profile regardless of the slug', () => {
    renderLink({ userId: SELF_ID, slug: 'me-myself', name: 'Me Myself' });
    expect(screen.getByRole('link', { name: "View Me Myself's profile" })).toHaveAttribute(
      'href',
      '/profile',
    );
  });

  it('renders pseudonymised identities without any link', () => {
    renderLink({ name: 'Reviewer 2' });
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('Reviewer 2')).toBeInTheDocument();
  });
});
