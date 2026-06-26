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

import Alert from '@mui/material/Alert';
import Snackbar from '@mui/material/Snackbar';
import type { ToastState } from './useToast';

/** Renders the toast produced by {@link useToast} — bottom-centre, filled, auto-hiding. */
export function AdminToast({ toast, onClose }: { toast: ToastState | null; onClose: () => void }) {
  return (
    <Snackbar
      open={toast !== null}
      autoHideDuration={4000}
      onClose={onClose}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      {toast ? (
        <Alert severity={toast.severity} onClose={onClose} variant="filled">
          {toast.message}
        </Alert>
      ) : undefined}
    </Snackbar>
  );
}
