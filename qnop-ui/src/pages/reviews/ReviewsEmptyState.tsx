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

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { keyframes } from '@mui/material/styles';
import { CircleCheckBig, PenLine, Plus, ShieldCheck } from 'lucide-react';
import { ReviewIllustration } from '../../components/reviews/list/ReviewIllustration';

const fadeUp = keyframes`
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
`;

/** A staggered fade-up, disabled under reduced-motion. */
function rise(delayMs: number) {
  return {
    animation: `${fadeUp} 520ms cubic-bezier(0.16, 1, 0.3, 1) both`,
    animationDelay: `${delayMs}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  } as const;
}

const PERKS: { icon: ReactNode; label: string }[] = [
  { icon: <PenLine size={15} />, label: 'Annotate line by line' },
  { icon: <CircleCheckBig size={15} />, label: 'Resolve open points' },
  { icon: <ShieldCheck size={15} />, label: 'Finalize with a trail' },
];

/**
 * The "no reviews yet" state of the reviews overview (issue #251/#470), reframed
 * as a launchpad: an animated fresh-document illustration, an encouraging
 * headline, motivating perk pills, and a real primary action to start the first
 * review. Same visual language as the My Teams empty state — full-width, brand
 * glow, staggered reveal.
 */
export function ReviewsEmptyState({ onNewReview }: { onNewReview: () => void }) {
  return (
    <Paper
      variant="outlined"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        px: { xs: 3, sm: 5 },
        py: { xs: 5, sm: 7 },
        textAlign: 'center',
        backgroundImage:
          'radial-gradient(90% 120% at 50% -10%, rgba(18,144,239,0.12), transparent 62%)',
      }}
    >
      <Stack sx={{ alignItems: 'center', maxWidth: 560, mx: 'auto' }}>
        <Box sx={{ width: '100%', maxWidth: 340, mb: 1 }}>
          <ReviewIllustration />
        </Box>

        <Typography
          sx={{
            ...rise(120),
            fontSize: 11.5,
            fontWeight: 700,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            color: 'primary.main',
          }}
        >
          Reviews
        </Typography>

        <Typography
          component="h2"
          sx={{
            ...rise(180),
            fontSize: { xs: 24, sm: 28 },
            fontWeight: 800,
            mt: 0.75,
            textWrap: 'balance',
          }}
        >
          Start your first review
        </Typography>

        <Typography
          sx={{ ...rise(240), color: 'text.secondary', fontSize: 15, lineHeight: 1.6, mt: 1.25 }}
        >
          A review is a document marked up line by line — raise points, resolve them together, and
          finalize with a clean trail. There’s nothing here yet.
        </Typography>

        <Stack
          direction="row"
          spacing={1}
          sx={{ ...rise(320), flexWrap: 'wrap', justifyContent: 'center', gap: 1, mt: 2.5 }}
        >
          {PERKS.map((perk) => (
            <Box
              key={perk.label}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                px: 1.5,
                py: 0.75,
                borderRadius: 999,
                fontSize: 13,
                fontWeight: 600,
                color: 'text.secondary',
                bgcolor: (t) => t.qnop.surface2,
                border: 1,
                borderColor: 'divider',
                '& svg': { color: 'primary.main' },
              }}
            >
              {perk.icon}
              {perk.label}
            </Box>
          ))}
        </Stack>

        <Box sx={{ ...rise(400), mt: 3.5 }}>
          <Button
            variant="contained"
            size="large"
            startIcon={<Plus size={18} />}
            onClick={onNewReview}
          >
            New review
          </Button>
          <Typography sx={{ mt: 1.25, fontSize: 13, color: 'text.disabled' }}>
            Upload a document to begin.
          </Typography>
        </Box>
      </Stack>
    </Paper>
  );
}
