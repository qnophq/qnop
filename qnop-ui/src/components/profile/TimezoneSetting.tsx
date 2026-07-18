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

import { useEffect, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Globe, LocateFixed } from 'lucide-react';
import { TimezonePicker } from '../TimezonePicker';
import { zoneOffsetLabel } from '../../utils/timezoneOptions';

const MONO = '"JetBrains Mono", ui-monospace, monospace';

interface TimezoneSettingProps {
  /** The active display zone (the user's stored preference, or the workspace default). */
  value: string;
  /** Whether {@code value} is the user's own stored choice vs. the inherited default. */
  isExplicit: boolean;
  /** A save is in flight. */
  saving: boolean;
  onChange: (zone: string) => void;
}

/** The device's own IANA zone, used for the one-tap "detect" shortcut. */
function deviceZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/**
 * A quiet, live readout of the current time in {@code zone} (issue #465): a
 * gently pulsing dot and a muted monospace HH:mm with its offset. Deliberately
 * understated — it sits beside the picker to confirm the choice at a glance, not
 * to draw attention. The dot conveys "live" so the minute-resolution time needs
 * no restless ticking seconds; the pulse honours reduced-motion.
 */
function ClockPreview({ zone, now }: { zone: string; now: Date }) {
  const theme = useTheme();
  const brand = theme.qnop.brand.blue;

  const time = useMemo(() => {
    try {
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: zone,
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      }).format(now);
    } catch {
      return '—';
    }
  }, [zone, now]);
  const offset = zoneOffsetLabel(zone, now);

  return (
    <Stack
      direction="row"
      spacing={1.25}
      title={`Current time in ${zone.replace(/_/g, ' ')}`}
      sx={{
        flex: { sm: '1 1 0' },
        minWidth: 0,
        alignItems: 'center',
        alignSelf: { xs: 'flex-start', sm: 'stretch' },
        pl: { sm: 2 },
        borderLeft: { sm: `1px solid ${theme.palette.divider}` },
      }}
    >
      <Box aria-hidden sx={{ position: 'relative', width: 7, height: 7, flexShrink: 0 }}>
        <Box
          sx={{ position: 'absolute', inset: 0, borderRadius: '50%', bgcolor: alpha(brand, 0.85) }}
        />
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            bgcolor: brand,
            animation: 'tzPulse 2.4s ease-out infinite',
            '@keyframes tzPulse': {
              '0%': { transform: 'scale(1)', opacity: 0.45 },
              '70%, 100%': { transform: 'scale(2.6)', opacity: 0 },
            },
            '@media (prefers-reduced-motion: reduce)': { animation: 'none', opacity: 0 },
          }}
        />
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography
          sx={{
            fontFamily: MONO,
            fontSize: 16,
            fontWeight: 500,
            lineHeight: 1.1,
            color: 'text.secondary',
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {time}
        </Typography>
        <Typography sx={{ fontSize: 11, color: 'text.disabled', letterSpacing: '0.02em' }}>
          {offset ? `${offset} · now` : 'now'}
        </Typography>
      </Box>
    </Stack>
  );
}

/**
 * The per-user display-timezone control (issue #465): a searchable, offset-sorted
 * zone picker with a quiet live clock beside it that reads the current moment in
 * the selected zone. Cohesive with the profile's branded card system; the clock
 * stays understated so the picker leads.
 */
export function TimezoneSetting({ value, isExplicit, saving, onChange }: TimezoneSettingProps) {
  const theme = useTheme();
  const brand = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  // A per-second tick keeps the minute rollover crisp; the interval is the only side effect.
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const device = deviceZone();

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3 } }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Box
          aria-hidden
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 38,
            height: 38,
            borderRadius: '11px',
            color: brand,
            bgcolor: alpha(brand, dark ? 0.18 : 0.1),
            border: `1px solid ${alpha(brand, 0.3)}`,
          }}
        >
          <Globe size={19} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 16, fontWeight: 600 }}>Time zone</Typography>
          <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.25 }}>
            Dates and times across qnop appear in this zone.
          </Typography>
        </Box>
      </Stack>

      <Divider sx={{ my: 2 }} />

      {/* Picker leads, the live time trails quietly beside it. */}
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ alignItems: { sm: 'stretch' } }}
      >
        <Box sx={{ flex: { sm: '1 1 0' }, minWidth: 0, alignSelf: 'center', width: '100%' }}>
          <TimezonePicker value={value} onChange={onChange} disabled={saving} />
        </Box>
        <ClockPreview zone={value} now={now} />
      </Stack>

      <Stack
        direction="row"
        spacing={1}
        sx={{ mt: 1.5, alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
      >
        {device !== value && (
          <Button
            size="small"
            variant="text"
            startIcon={<LocateFixed size={15} />}
            disabled={saving}
            onClick={() => onChange(device)}
            sx={{ textTransform: 'none' }}
          >
            Use my device zone ({device.replace(/_/g, ' ')})
          </Button>
        )}
        {!isExplicit && (
          <Typography color="text.secondary" sx={{ fontSize: 12.5 }}>
            Currently following the workspace default.
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}
