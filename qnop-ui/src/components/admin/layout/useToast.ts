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

import { useCallback, useState } from 'react';

export type ToastSeverity = 'success' | 'error';
export interface ToastState {
  message: string;
  severity: ToastSeverity;
}

/** The shared notify callback signature passed down to child components. */
export type Notify = (message: string, severity?: ToastSeverity) => void;

/**
 * Admin-page toast state: a single, transient success/error notification. Pairs
 * with the `AdminToast` component for rendering, so every admin surface gets
 * identical placement, timing and styling without re-declaring the Snackbar.
 */
export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null);
  const notify = useCallback<Notify>(
    (message, severity = 'success') => setToast({ message, severity }),
    [],
  );
  const clear = useCallback(() => setToast(null), []);
  return { toast, notify, clear };
}
