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
import Autocomplete from '@mui/material/Autocomplete';
import TextField from '@mui/material/TextField';
import { styled } from '@mui/material/styles';
import { buildTimezoneOptions, type TimezoneOption } from '../utils/timezoneOptions';

const MONO = '"JetBrains Mono", ui-monospace, monospace';

/**
 * The option row, as two `styled` components rather than `Box`/`Typography`
 * carrying an `sx` prop (issue #716).
 *
 * The engine lists ~418 zones and the listbox is not virtualised, so opening
 * the picker renders every one of them. An inline `sx` per element meant two
 * emotion serialisations per row — ~836 per open; `styled` resolves them once
 * at module load, so a row costs a class name.
 *
 * Worth knowing before optimising further: this is a real but *minor* part of
 * the open cost. Measured in jsdom, opening went ~2.1 s → ~1.8 s, while 418
 * plain `<li>` render in ~0.1 s — so the remaining ~1.7 s is MUI's per-option
 * Autocomplete machinery, which only virtualisation or a capped option list
 * would remove.
 *
 * These carry no dynamic values, which is exactly why they can be hoisted; if a
 * future variant needs one, prefer a prop-driven `styled` over reintroducing
 * `sx` here.
 */
const OptionRow = styled('li')({
  display: 'flex',
  justifyContent: 'space-between',
  gap: 16,
});

const OptionOffset = styled('span')(({ theme }) => ({
  fontFamily: MONO,
  fontSize: 12,
  color: theme.palette.text.secondary,
  flexShrink: 0,
}));

interface TimezonePickerProps {
  /** The current IANA zone id. */
  value: string;
  onChange: (zone: string) => void;
  /** Field label; defaults to "Time zone". */
  label?: string;
  helperText?: string;
  error?: boolean;
  disabled?: boolean;
  /** Constrain the control's width (the admin form caps its fields at 480). */
  maxWidth?: number | string;
}

/**
 * A searchable, offset-sorted dropdown of every IANA time zone (issue #465) —
 * the shared control behind the profile's display-zone card and the admin
 * default-timezone setting, so both read and behave identically. Each row shows
 * its live UTC offset; the stored value is always folded in so it stays
 * selectable even if the engine no longer lists it.
 */
export function TimezonePicker({
  value,
  onChange,
  label = 'Time zone',
  helperText,
  error,
  disabled,
  maxWidth,
}: TimezonePickerProps) {
  const options = useMemo(() => buildTimezoneOptions(value), [value]);
  // `value` is folded into the options, so a match always exists; the [0] guard
  // keeps the type non-null for the clear-disabled Autocomplete.
  const selected = options.find((option) => option.zone === value) ?? options[0];

  return (
    <Autocomplete<TimezoneOption, false, true, false>
      options={options}
      value={selected}
      disabled={disabled}
      disableClearable
      autoHighlight
      sx={maxWidth ? { maxWidth } : undefined}
      onChange={(_event, option) => option && option.zone !== value && onChange(option.zone)}
      getOptionLabel={(option) => option.label}
      isOptionEqualToValue={(a, b) => a.zone === b.zone}
      renderInput={(params) => (
        <TextField
          {...params}
          label={label}
          placeholder="Search a city or region…"
          error={error}
          helperText={helperText}
        />
      )}
      renderOption={(props, option) => {
        const { key, ...rest } = props;
        return (
          <OptionRow key={key} {...rest}>
            <span>{option.label}</span>
            <OptionOffset>{option.offset}</OptionOffset>
          </OptionRow>
        );
      }}
    />
  );
}
