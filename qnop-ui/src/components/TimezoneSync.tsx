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

import { useEffect } from 'react';
import { useDisplayTimezone } from '../api/hooks/useDisplayTimezone';
import { useUiStore } from '../stores/uiStore';

/**
 * Resolves the active display timezone (user profile → application default → UTC,
 * issue #465, ADR-0041) and mirrors it into the UI store, so every component can
 * format timestamps via useFormatters() without each depending on a QueryClient.
 * Mounted once near the app root, inside the QueryClientProvider. Renders nothing.
 */
export function TimezoneSync() {
  const zone = useDisplayTimezone();
  const setDisplayTimeZone = useUiStore((s) => s.setDisplayTimeZone);

  useEffect(() => {
    setDisplayTimeZone(zone);
  }, [zone, setDisplayTimeZone]);

  return null;
}
