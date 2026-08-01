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
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Slide from '@mui/material/Slide';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { ChartNoAxesColumn } from 'lucide-react';
import { writeConsent } from './consent';
import { tokens } from '../theme/tokens';

interface ConsentBarProps {
  onDecide: (decision: 'granted' | 'denied') => void;
}

/**
 * The measurement question (issue #666).
 *
 * <p>At the bottom, deliberately: the top of the screen belongs to the operator's own notice
 * (issue #664), and two bars stacked above the sign-in form would bury the form. It is a strip
 * rather than a modal because it interrupts nothing — a visitor who ignores it is simply not
 * measured, which is the correct default and needs no dialog to enforce.
 *
 * <p>Both answers are one click and look equally clickable. A refusal that takes three clicks
 * through a "manage preferences" screen is not a question, it is an obstacle course.
 */
export function ConsentBar({ onDecide }: ConsentBarProps) {
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)');

  const decide = (decision: 'granted' | 'denied') => {
    writeConsent(decision);
    onDecide(decision);
  };

  return (
    <Slide direction="up" in appear timeout={reduceMotion ? 0 : 220}>
      <Paper
        elevation={0}
        role="dialog"
        aria-label="Usage measurement"
        sx={{
          position: 'fixed',
          left: { xs: 12, sm: 16 },
          right: { xs: 12, sm: 16 },
          bottom: { xs: 12, sm: 16 },
          zIndex: (theme) => theme.zIndex.snackbar,
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          gap: 1.5,
          px: { xs: 2, sm: 2.5 },
          py: 1.75,
          border: 1,
          borderColor: 'divider',
          borderRadius: `${tokens.radius.lg}px`,
          boxShadow: '0 18px 40px rgba(1, 33, 66, 0.18)',
          maxWidth: 720,
          mx: 'auto',
        }}
      >
        <Box aria-hidden sx={{ display: 'flex', color: 'primary.main', flexShrink: 0 }}>
          <ChartNoAxesColumn size={18} />
        </Box>
        <Typography variant="body2" sx={{ flex: 1, minWidth: 220, lineHeight: 1.5 }}>
          May we count which pages get used? No profiles, no document names — page addresses are
          reduced to their shape before they are counted, and the measurement never leaves this
          server directly.
        </Typography>
        <Box sx={{ display: 'flex', gap: 1, ml: 'auto' }}>
          <Button size="small" color="inherit" onClick={() => decide('denied')}>
            No thanks
          </Button>
          <Button size="small" variant="contained" onClick={() => decide('granted')}>
            Allow
          </Button>
        </Box>
      </Paper>
    </Slide>
  );
}
