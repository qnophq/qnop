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

import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { endOfDay } from 'date-fns';

interface DueDatePickerProps {
  /** The current due date as an ISO instant, or null when none is set. */
  value: string | null;
  /** Emits the new ISO instant, or null when cleared. */
  onChange: (iso: string | null) => void;
  /** Block past dates (used at creation; editing allows correcting to the past). */
  disablePast?: boolean;
  label?: string;
  disabled?: boolean;
}

/**
 * The review due-date picker (issue #295): a day-granular {@link DatePicker} that
 * stores the deadline as the end of the chosen day, so a review picked for "today"
 * stays due for the rest of the day rather than being instantly overdue. Clearable
 * — removing the value clears the deadline. Wraps its own {@link LocalizationProvider}
 * so callers need no app-level date adapter.
 */
export function DueDatePicker({
  value,
  onChange,
  disablePast = true,
  label = 'Due date',
  disabled,
}: DueDatePickerProps) {
  const parsed = value ? new Date(value) : null;
  const current = parsed && !Number.isNaN(parsed.getTime()) ? parsed : null;

  return (
    <LocalizationProvider dateAdapter={AdapterDateFns}>
      <DatePicker
        label={label}
        value={current}
        disabled={disabled}
        disablePast={disablePast}
        onChange={(next) => {
          if (!next || Number.isNaN(next.getTime())) {
            onChange(null);
            return;
          }
          onChange(endOfDay(next).toISOString());
        }}
        slotProps={{
          field: { clearable: true, onClear: () => onChange(null) },
          textField: {
            size: 'small',
            fullWidth: true,
            helperText: 'Optional — an informational deadline; overdue reviews are only flagged.',
          },
        }}
      />
    </LocalizationProvider>
  );
}
