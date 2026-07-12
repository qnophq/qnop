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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import {
  CalendarClock,
  Inbox,
  MessagesSquare,
  Rocket,
  Trophy,
  Upload,
  UserCheck,
  Users,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';

/** The quest path — the three moves from empty workspace to running review. */
const STEPS: { icon: LucideIcon; title: string; hint: string }[] = [
  { icon: Upload, title: 'Upload a document', hint: 'PDF in, review out.' },
  { icon: Users, title: 'Invite reviewers', hint: 'People or whole teams.' },
  { icon: MessagesSquare, title: 'Discuss & resolve', hint: 'Annotate, reply, settle.' },
];

/** The scoreboard the filled dashboard shows — previewed here as ghosts. */
const GHOST_TILES: { icon: LucideIcon; label: string }[] = [
  { icon: Inbox, label: 'Open reviews' },
  { icon: UserCheck, label: 'Waiting on you' },
  { icon: CalendarClock, label: 'Due soon' },
  { icon: Trophy, label: 'Resolved this week' },
];

const fadeUp = {
  '@keyframes emptyDashFadeUp': {
    from: { opacity: 0, transform: 'translateY(10px)' },
    to: { opacity: 1, transform: 'translateY(0)' },
  },
};

/**
 * The first-run dashboard (issue #469): the same playful, gamified language as
 * the filled command centre (#454), but staged as a launch pad — an
 * atmospheric hero with floating achievement stickers, a three-step quest path
 * whose first step is armed, and a ghost preview of the scoreboard that starts
 * counting with the first review. Entrance is a short staggered rise;
 * reduced-motion users get everything in place, still.
 */
export function EmptyDashboard() {
  const theme = useTheme();
  const navigate = useNavigate();
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  const stagger = (index: number) => ({
    ...fadeUp,
    animation: `emptyDashFadeUp 0.45s ${theme.transitions.easing.easeOut} both`,
    animationDelay: `${index * 90}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  });

  return (
    <Stack spacing={2.5} data-testid="empty-dashboard">
      {/* Hero: atmosphere instead of absence. */}
      <Paper
        variant="outlined"
        sx={{
          ...stagger(0),
          position: 'relative',
          overflow: 'hidden',
          borderRadius: '16px',
          p: { xs: 3, md: 4.5 },
          background: `
            radial-gradient(56% 90% at 85% 12%, ${alpha(blue, dark ? 0.2 : 0.1)} 0%, transparent 100%),
            radial-gradient(40% 70% at 100% 90%, ${alpha(theme.palette.warning.main, dark ? 0.12 : 0.07)} 0%, transparent 100%),
            ${theme.palette.background.paper}
          `,
        }}
      >
        <Box
          sx={{
            display: 'grid',
            gap: 3,
            gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) auto' },
            alignItems: 'center',
          }}
        >
          <Box>
            <Typography
              sx={{
                fontSize: { xs: 24, md: 28 },
                fontWeight: 800,
                letterSpacing: '-0.02em',
                lineHeight: 1.2,
              }}
            >
              Your command centre is ready for its first mission.
            </Typography>
            <Typography color="text.secondary" sx={{ mt: 1, maxWidth: 520 }}>
              Every review you run or join lands here — deadlines, replies to your comments, and the
              wins you resolve. It just needs a document to chew on.
            </Typography>
            <Button
              variant="contained"
              size="large"
              startIcon={<Rocket size={18} />}
              onClick={() => navigate('/reviews')}
              sx={{ mt: 3 }}
            >
              Start your first review
            </Button>
          </Box>

          {/* Sticker composition: the badge you're about to earn. */}
          <Box
            aria-hidden
            sx={{
              display: { xs: 'none', md: 'block' },
              position: 'relative',
              width: 180,
              height: 150,
              mr: 1,
              '@keyframes emptyDashFloat': {
                '0%, 100%': { transform: 'translateY(0)' },
                '50%': { transform: 'translateY(-6px)' },
              },
            }}
          >
            <Box
              sx={{
                position: 'absolute',
                left: 34,
                top: 22,
                width: 96,
                height: 96,
                borderRadius: '28px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: blue,
                bgcolor: alpha(blue, dark ? 0.18 : 0.1),
                border: `1px solid ${alpha(blue, 0.35)}`,
                animation: 'emptyDashFloat 5s ease-in-out infinite',
                '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
              }}
            >
              <Rocket size={40} />
            </Box>
            {[
              { icon: Trophy, color: theme.palette.warning.main, right: 0, top: 0, delay: '1.2s' },
              {
                icon: MessagesSquare,
                color: theme.palette.success.main,
                right: 18,
                top: 96,
                delay: '2.4s',
              },
            ].map(({ icon: Icon, color, right, top, delay }, i) => (
              <Box
                key={i}
                sx={{
                  position: 'absolute',
                  right,
                  top,
                  width: 44,
                  height: 44,
                  borderRadius: '14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color,
                  bgcolor: alpha(color, dark ? 0.18 : 0.12),
                  border: `1px solid ${alpha(color, 0.3)}`,
                  animation: 'emptyDashFloat 5s ease-in-out infinite',
                  animationDelay: delay,
                  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                }}
              >
                <Icon size={20} />
              </Box>
            ))}
          </Box>
        </Box>
      </Paper>

      {/* Quest path: step 1 armed, the rest visibly upcoming. */}
      <Paper variant="outlined" sx={{ ...stagger(1), borderRadius: '16px', p: { xs: 2.5, md: 3 } }}>
        <Typography sx={{ fontWeight: 700, mb: 2 }}>Three moves to your first review</Typography>
        <Box
          sx={{
            display: 'grid',
            gap: { xs: 2, md: 2.5 },
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
          }}
        >
          {STEPS.map(({ icon: Icon, title, hint }, index) => {
            const armed = index === 0;
            const stepColor = armed ? blue : theme.palette.text.secondary;
            return (
              <Stack key={title} direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
                <Box
                  aria-hidden
                  sx={{
                    position: 'relative',
                    width: 40,
                    height: 40,
                    flexShrink: 0,
                    borderRadius: '12px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: stepColor,
                    bgcolor: alpha(stepColor, armed ? 0.12 : 0.07),
                    border: `1px ${armed ? 'solid' : 'dashed'} ${alpha(stepColor, armed ? 0.45 : 0.35)}`,
                    // The armed step breathes — the path's "you are here".
                    ...(armed && {
                      '@keyframes emptyDashPulse': {
                        '0%': { boxShadow: `0 0 0 0 ${alpha(blue, 0.35)}` },
                        '70%': { boxShadow: `0 0 0 9px ${alpha(blue, 0)}` },
                        '100%': { boxShadow: `0 0 0 0 ${alpha(blue, 0)}` },
                      },
                      animation: 'emptyDashPulse 2.4s ease-out infinite',
                      '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                    }),
                  }}
                >
                  <Icon size={18} />
                </Box>
                <Box sx={{ minWidth: 0 }}>
                  <Typography sx={{ fontWeight: 600, fontSize: 14.5 }}>
                    <Box component="span" sx={{ color: stepColor, fontWeight: 800, mr: 0.75 }}>
                      {index + 1}
                    </Box>
                    {title}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {hint}
                  </Typography>
                </Box>
              </Stack>
            );
          })}
        </Box>
      </Paper>

      {/* Ghost scoreboard: what this page will measure, at zero for now. */}
      <Box sx={stagger(2)}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(4, 1fr)' },
            gap: 1.5,
          }}
        >
          {GHOST_TILES.map(({ icon: Icon, label }) => (
            <Paper
              key={label}
              variant="outlined"
              sx={{
                px: 2,
                py: 1.5,
                borderRadius: '12px',
                borderStyle: 'dashed',
                bgcolor: 'transparent',
              }}
            >
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', opacity: 0.55 }}>
                <Box
                  aria-hidden
                  sx={{
                    width: 34,
                    height: 34,
                    borderRadius: '10px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                    color: 'text.secondary',
                    bgcolor: alpha(theme.palette.text.secondary, 0.1),
                  }}
                >
                  <Icon size={16} />
                </Box>
                <Box sx={{ minWidth: 0 }}>
                  <Typography
                    sx={{ fontSize: '1.4rem', fontWeight: 800, lineHeight: 1.2 }}
                    color="text.secondary"
                  >
                    0
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap component="p">
                    {label}
                  </Typography>
                </Box>
              </Stack>
            </Paper>
          ))}
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
          Your scoreboard — it starts counting with your first review.
        </Typography>
      </Box>
    </Stack>
  );
}
