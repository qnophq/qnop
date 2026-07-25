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

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RouterProvider } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme/theme';
import { router } from './index';

/**
 * Integration over the REAL route table (issue #611): the branded states are
 * reachable at their addresses, outside the auth shell - 503/offline must
 * work when nothing else does, so none of these may sit behind a login.
 */
const CASES: [string, RegExp][] = [
  ['/403', /binder is locked/i],
  ['/409', /two pins landed on the same line/i],
  ['/500', /shredder grabbed the wrong document/i],
  ['/503', /out to lunch/i],
  ['/429', /stamp champion/i],
  ['/offline', /paper cups/i],
];

describe('error routes (#611)', () => {
  afterEach(cleanup);

  it.each(CASES)('serves the branded state at %s without auth', async (path, headline) => {
    await router.navigate(path);
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <RouterProvider router={router} />
      </ThemeProvider>,
    );
    expect(await screen.findByText(headline)).toBeInTheDocument();
  });
});
