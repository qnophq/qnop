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
import { RouterProvider, createMemoryRouter } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { RouteErrorPage } from './RouteErrorPage';

function renderWithThrow(thrower: () => never) {
  const Boom = () => thrower();
  const router = createMemoryRouter([
    { path: '/', element: <Boom />, errorElement: <RouteErrorPage /> },
  ]);
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <RouterProvider router={router} />
    </ThemeProvider>,
  );
}

describe('RouteErrorPage (#611)', () => {
  it('renders the branded 500 for an unexpected navigation throw', () => {
    renderWithThrow(() => {
      throw new Error('boom');
    });
    expect(screen.getByText('500')).toBeInTheDocument();
    expect(screen.getByText(/not your doing/i)).toBeInTheDocument();
  });

  it('renders the branded 404 for a 404 route response', async () => {
    // Only loader/action throws become route error responses - mirror that.
    const router = createMemoryRouter([
      {
        path: '/',
        loader: () => {
          throw new Response('gone', { status: 404 });
        },
        element: <div />,
        errorElement: <RouteErrorPage />,
      },
    ]);
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <RouterProvider router={router} />
      </ThemeProvider>,
    );
    expect(await screen.findByText('404')).toBeInTheDocument();
    expect(screen.getByText(/paper plane/)).toBeInTheDocument();
  });
});
