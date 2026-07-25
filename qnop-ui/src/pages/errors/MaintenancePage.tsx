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
import { MaintenanceIllustration } from './illustrations';

/** 503 - the boss is patching itself; reassuring, back shortly (issue #611). */
export function MaintenancePage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      code="503"
      title="Out to lunch - back shortly"
      message="qnop is patching itself up. Your reviews are safe in the cabinet; give it a moment and try again."
      illustration={<MaintenanceIllustration />}
      tone="alert"
      primaryAction={{ label: 'Retry', onClick: () => navigate(0) }}
    />
  );
}
