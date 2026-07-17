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
import Chip from '@mui/material/Chip';
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
 * The per-user display-timezone control (issue #465). A live world-clock reads
 * the current moment in the chosen zone — turning an abstract IANA id into
 * something you can verify at a glance — over a searchable, offset-sorted zone
 * picker. Cohesive with the profile's branded card system; the clock is its one
 * deliberate flourish.
 */
export function TimezoneSetting({ value, isExplicit, saving, onChange }: TimezoneSettingProps) {
  const theme = useTheme();
  const brand = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  // A per-second tick drives the live clock; the interval is the only side effect.
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const device = deviceZone();

  const clock = useMemo(() => {
    try {
      return new Intl.DateTimeFormat('en-GB', {
        timeZone: value,
        weekday: 'short',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(now);
    } catch {
      return '—';
    }
  }, [value, now]);

  const offset = zoneOffsetLabel(value, now);

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

      {/* The live world-clock: the abstract id made concrete and checkable. */}
      <Box
        sx={{
          position: 'relative',
          overflow: 'hidden',
          borderRadius: '13px',
          px: { xs: 2, sm: 2.5 },
          py: 2,
          mb: 2,
          border: `1px solid ${alpha(brand, dark ? 0.28 : 0.2)}`,
          background: `
            radial-gradient(120% 140% at 100% 0%, ${alpha(brand, dark ? 0.2 : 0.1)} 0%, transparent 60%),
            ${alpha(theme.palette.text.primary, dark ? 0.04 : 0.02)}
          `,
        }}
      >
        <Stack
          direction="row"
          spacing={2}
          sx={{ alignItems: 'baseline', justifyContent: 'space-between', flexWrap: 'wrap' }}
        >
          <Box sx={{ minWidth: 0 }}>
            <Typography
              sx={{
                fontFamily: MONO,
                fontSize: { xs: 30, sm: 36 },
                fontWeight: 600,
                lineHeight: 1,
                letterSpacing: '0.01em',
                color: 'text.primary',
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {clock}
            </Typography>
            <Typography color="text.secondary" noWrap sx={{ fontSize: 13, mt: 0.75 }}>
              {value.replace(/_/g, ' ')}
            </Typography>
          </Box>
          {offset && (
            <Chip
              label={offset}
              size="small"
              sx={{
                fontFamily: MONO,
                fontWeight: 600,
                color: brand,
                bgcolor: alpha(brand, dark ? 0.2 : 0.12),
                border: `1px solid ${alpha(brand, 0.3)}`,
              }}
            />
          )}
        </Stack>
      </Box>

      <TimezonePicker value={value} onChange={onChange} disabled={saving} />

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
