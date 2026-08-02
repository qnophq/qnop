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

import type { ReactNode } from 'react';
import Alert from '@mui/material/Alert';

/**
 * A quota that has been reached (issue #690).
 *
 * <p>One component for all four, because they were drifting: the user list had a
 * warning while teams made do with a number in the page description, and the
 * same ceiling looked like two different kinds of event depending on which
 * screen you were standing on.
 *
 * <p>The caller supplies the specific sentence — what is full, and how to free
 * one, which differs per quota (deleting an account, finalizing a review). The
 * closing sentence is fixed, because it is the same answer every time and the
 * one an administrator most needs: this ceiling is not in the settings they
 * administer, so looking for it there is wasted time.
 */
export function QuotaAlert({ children }: { children: ReactNode }) {
  return (
    <Alert severity="warning">
      {children} The ceiling is set where this deployment is configured, not in the admin settings.
    </Alert>
  );
}
