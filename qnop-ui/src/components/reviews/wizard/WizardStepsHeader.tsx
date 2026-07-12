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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Check } from 'lucide-react';

export interface WizardStep {
  label: string;
}

interface WizardStepsHeaderProps {
  steps: WizardStep[];
  /** 1-based index of the active step. */
  active: number;
}

/** The wizard's progress strip (prototype `wizard.jsx`): numbered dots + connectors. */
export function WizardStepsHeader({ steps, active }: WizardStepsHeaderProps) {
  const theme = useTheme();
  return (
    <Paper variant="outlined" sx={{ px: 2.5, py: 2 }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        {steps.map((step, i) => {
          const n = i + 1;
          const isDone = active > n;
          const isActive = active === n;
          return (
            <Stack
              key={step.label}
              direction="row"
              spacing={1.25}
              sx={{ alignItems: 'center', flex: 1, minWidth: 0 }}
            >
              <Box
                sx={{
                  width: 28,
                  height: 28,
                  borderRadius: '50%',
                  flexShrink: 0,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: 12,
                  fontWeight: 600,
                  fontVariantNumeric: 'tabular-nums',
                  bgcolor: isDone
                    ? theme.palette.success.main
                    : isActive
                      ? theme.qnop.brand.blue
                      : theme.qnop.surface2,
                  color: isDone || isActive ? '#fff' : 'text.disabled',
                  boxShadow: isActive ? `0 0 0 3px ${theme.qnop.brand.blue}33` : 'none',
                  // The active step breathes, like the launch pad's armed step (#469).
                  ...(isActive && {
                    '@keyframes wizardStepPulse': {
                      '0%': { boxShadow: `0 0 0 0 ${theme.qnop.brand.blue}59` },
                      '70%': { boxShadow: `0 0 0 8px ${theme.qnop.brand.blue}00` },
                      '100%': { boxShadow: `0 0 0 0 ${theme.qnop.brand.blue}00` },
                    },
                    animation: 'wizardStepPulse 2.4s ease-out infinite',
                    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                  }),
                }}
                aria-current={isActive ? 'step' : undefined}
              >
                {isDone ? <Check size={14} aria-label={`${step.label} done`} /> : n}
              </Box>
              <Box sx={{ minWidth: 0 }}>
                <Typography
                  variant="caption"
                  sx={{
                    display: 'block',
                    textTransform: 'uppercase',
                    letterSpacing: '0.06em',
                    fontSize: 10,
                    color: 'text.disabled',
                    lineHeight: 1.4,
                  }}
                >
                  Step {n}
                </Typography>
                <Typography
                  variant="body2"
                  noWrap
                  sx={{
                    fontWeight: 500,
                    color: active >= n ? 'text.primary' : 'text.disabled',
                    lineHeight: 1.3,
                  }}
                >
                  {step.label}
                </Typography>
              </Box>
              {i < steps.length - 1 && (
                <Box
                  aria-hidden
                  sx={{
                    flex: 1,
                    height: 2,
                    borderRadius: 99,
                    mx: 1,
                    bgcolor: isDone ? theme.palette.success.main : theme.palette.divider,
                    transition: 'background-color 200ms ease',
                  }}
                />
              )}
            </Stack>
          );
        })}
      </Stack>
    </Paper>
  );
}
