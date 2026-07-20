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

import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import TextField, { type TextFieldProps } from '@mui/material/TextField';
import { Search, X } from 'lucide-react';

interface ClearableSearchFieldProps extends Omit<
  TextFieldProps,
  'value' | 'onChange' | 'select' | 'multiline' | 'slotProps'
> {
  /** The controlled input value. */
  value: string;
  /** Called with the new value on every keystroke. */
  onValueChange: (value: string) => void;
  /**
   * Called when the clear button is clicked; defaults to `onValueChange('')`.
   * Provide one when clearing must do more — e.g. skip a debounce or reset paging.
   */
  onClear?: () => void;
  /** aria-label of the clear button. */
  clearLabel?: string;
  /** Hide the leading search icon (e.g. when the field's label already says "Search"). */
  hideSearchIcon?: boolean;
  /** aria-label on the input element itself. */
  inputAriaLabel?: string;
  /** Maximum input length. */
  maxLength?: number;
}

/**
 * The app-wide search/filter text field (issue #541): a leading search icon
 * and an X clear button that appears once text is entered — one markup for
 * the teams, users and configuration searches (and future ones) instead of a
 * hand-rolled copy per page.
 */
export function ClearableSearchField({
  value,
  onValueChange,
  onClear,
  clearLabel = 'Clear search',
  hideSearchIcon = false,
  inputAriaLabel,
  maxLength,
  ...textFieldProps
}: ClearableSearchFieldProps) {
  return (
    <TextField
      size="small"
      value={value}
      onChange={(e) => onValueChange(e.target.value)}
      slotProps={{
        htmlInput: maxLength === undefined ? undefined : { maxLength },
        input: {
          'aria-label': inputAriaLabel,
          startAdornment: hideSearchIcon ? undefined : (
            <InputAdornment position="start">
              <Search size={16} aria-hidden />
            </InputAdornment>
          ),
          endAdornment: value ? (
            <InputAdornment position="end">
              <IconButton
                aria-label={clearLabel}
                size="small"
                edge="end"
                onClick={onClear ?? (() => onValueChange(''))}
              >
                <X size={16} />
              </IconButton>
            </InputAdornment>
          ) : undefined,
        },
      }}
      {...textFieldProps}
    />
  );
}
