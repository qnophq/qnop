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

import { useMemo } from 'react';
import { useUiStore } from '../stores/uiStore';
import { formatDateTime, formatDueDate, formatRelative, isPast } from '../utils/formatDate';
import { shortRelativeTime } from '../utils/relativeTime';

/**
 * Date/time formatters bound to the viewer's active display zone (issue #465,
 * ADR-0041). This is the single seam through which components render timestamps:
 * every returned formatter carries the resolved timezone (user profile →
 * application default → UTC), so no component renders in the raw browser zone.
 *
 * The returned object is memoized on the zone, so a component re-render does not
 * rebuild the closures and the wrapped formatters keep a stable identity. The zone
 * is read from the UI store (populated by TimezoneSync), so a component can format
 * without depending on a QueryClient being present.
 */
export function useFormatters() {
  const timeZone = useUiStore((s) => s.displayTimeZone);
  return useMemo(
    () => ({
      /** The active IANA display zone, e.g. for showing "(Europe/Berlin)" next to a raw time. */
      timeZone,
      formatDateTime: (iso: string | null | undefined) => formatDateTime(iso, timeZone),
      formatRelative: (iso: string | null | undefined) => formatRelative(iso, timeZone),
      formatDueDate: (iso: string | null | undefined) => formatDueDate(iso, timeZone),
      /** Zone-independent (compares epoch millis); re-exported so callers use one seam. */
      isPast,
      shortRelativeTime: (iso: string, now?: Date) => shortRelativeTime(iso, now, timeZone),
    }),
    [timeZone],
  );
}
