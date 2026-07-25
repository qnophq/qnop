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

import { useNavigate } from 'react-router';
import { ErrorState } from './ErrorState';
import { ConflictIllustration } from './illustrations';

/**
 * 409, full-page variant only (issue #611): the navigation target changed
 * under the user (e.g. an optimistic-concurrency clash, ADR-0030). Routine
 * in-page conflicts stay inline where they are handled today.
 */
export function ConflictPage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      code="409"
      title="Two pins landed on the same line"
      message="Someone changed this while you were on your way to it. Reload to see the current state - nothing of yours was lost."
      illustration={<ConflictIllustration />}
      tone="alert"
      primaryAction={{ label: 'Reload this page', onClick: () => navigate(0) }}
      secondaryAction={{ label: 'Back to dashboard', to: '/' }}
    />
  );
}
