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

import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Check, Rocket } from 'lucide-react';
import type { LaunchItem } from './wizardModel';
import { launchReadiness } from './wizardModel';

/**
 * The wizard's gamified sidekick (issue #469): a mission checklist that ticks
 * along with the form and condenses it into one "launch readiness" number.
 * Required items carry the launch; the optional extras top the score up to
 * 100%. Pure display — every check derives from the live form state.
 */
export function LaunchChecklist({ items }: { items: LaunchItem[] }) {
  const theme = useTheme();
  const blue = theme.qnop.brand.blue;
  const readiness = launchReadiness(items);
  const ready = items.filter((item) => !item.optional).every((item) => item.done);
  const barColor = ready ? theme.palette.success.main : blue;

  return (
    <Paper variant="outlined" sx={{ p: 2.5, borderRadius: '16px' }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 2 }}>
        <Box
          aria-hidden
          sx={{
            width: 38,
            height: 38,
            borderRadius: '12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            color: barColor,
            bgcolor: alpha(barColor, theme.qnop.mode === 'dark' ? 0.18 : 0.1),
            transition: 'color 300ms ease, background-color 300ms ease',
          }}
        >
          <Rocket size={18} />
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 14.5, lineHeight: 1.3 }}>
            Launch readiness
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {ready
              ? 'Ready for lift-off — extras are bonus points.'
              : 'Fill the essentials to launch.'}
          </Typography>
        </Box>
        <Typography
          sx={{
            fontWeight: 800,
            fontSize: 20,
            fontVariantNumeric: 'tabular-nums',
            color: barColor,
            transition: 'color 300ms ease',
          }}
        >
          {readiness}%
        </Typography>
      </Stack>

      <LinearProgress
        variant="determinate"
        value={readiness}
        aria-label="Launch readiness"
        sx={{
          height: 6,
          borderRadius: 99,
          mb: 2,
          bgcolor: alpha(theme.palette.text.secondary, 0.12),
          '& .MuiLinearProgress-bar': {
            borderRadius: 99,
            bgcolor: barColor,
            transition: 'transform 400ms ease, background-color 300ms ease',
          },
        }}
      />

      <Stack spacing={1.25}>
        {items.map((item) => (
          <Stack key={item.label} direction="row" spacing={1.25} sx={{ alignItems: 'center' }}>
            <Box
              aria-hidden
              sx={{
                width: 22,
                height: 22,
                borderRadius: '50%',
                flexShrink: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#fff',
                bgcolor: item.done ? theme.palette.success.main : 'transparent',
                border: item.done
                  ? 'none'
                  : `1.5px dashed ${alpha(theme.palette.text.secondary, 0.45)}`,
                // A fulfilled item pops in — the checklist's little reward.
                ...(item.done && {
                  '@keyframes launchTickPop': {
                    from: { transform: 'scale(0.5)' },
                    to: { transform: 'scale(1)' },
                  },
                  animation: 'launchTickPop 220ms ease-out',
                  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                }),
              }}
            >
              {item.done && <Check size={13} strokeWidth={3} />}
            </Box>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography
                variant="body2"
                sx={{
                  fontWeight: item.done ? 600 : 400,
                  color: item.done ? 'text.primary' : 'text.secondary',
                }}
              >
                {item.label}
                {item.optional && (
                  <Box
                    component="span"
                    sx={{
                      ml: 0.75,
                      fontSize: 10,
                      fontWeight: 600,
                      letterSpacing: '0.05em',
                      textTransform: 'uppercase',
                      color: 'text.disabled',
                    }}
                  >
                    bonus
                  </Box>
                )}
              </Typography>
              {item.detail && (
                <Typography variant="caption" color="text.secondary">
                  {item.detail}
                </Typography>
              )}
            </Box>
          </Stack>
        ))}
      </Stack>
    </Paper>
  );
}
