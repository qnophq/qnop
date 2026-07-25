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
import { OfflineIllustration } from './illustrations';

/** Offline - calm, not alarming; the string will be re-tied (issue #611). */
export function OfflinePage() {
  const navigate = useNavigate();
  return (
    <ErrorState
      title="The string between our paper cups snapped"
      message="You seem to be offline. Nothing is lost - once the connection is back, everything picks up where it stopped."
      illustration={<OfflineIllustration />}
      tone="alert"
      primaryAction={{ label: 'Retry connection', onClick: () => navigate(0) }}
    />
  );
}
