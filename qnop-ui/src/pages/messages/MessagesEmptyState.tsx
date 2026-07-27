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
import { AtSign, CheckCircle2, FileText, MessageSquare, PartyPopper } from 'lucide-react';
import { Link as RouterLink } from 'react-router';
import { InboxIllustration } from '../../components/notifications/InboxIllustration';

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
  { icon: <AtSign size={15} />, label: 'Mentions that name you' },
  { icon: <MessageSquare size={15} />, label: 'Replies on your threads' },
  { icon: <CheckCircle2 size={15} />, label: 'Decisions and new versions' },
];

/**
 * The "nothing has ever landed here" state of the inbox (issue #538), in the
 * same launchpad language as the reviews and my-teams empty states: an animated
 * scene, an eyebrow, a headline, motivating perk pills and a real way onward.
 *
 * It frames an empty inbox as *quiet and wired up*, not as *broken* — there is
 * nothing the user did wrong and nothing to fix. The action therefore points at
 * where activity is actually born (a review), because you cannot create a
 * notification directly.
 */
export function MessagesEmptyState() {
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
          <InboxIllustration />
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
          Messages
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
          Your inbox is listening
        </Typography>

        <Typography
          sx={{ ...rise(240), color: 'text.secondary', fontSize: 15, lineHeight: 1.6, mt: 1.25 }}
        >
          Nothing has landed yet. The moment someone names you in a comment, answers one of your
          annotations or moves a review along, it shows up here — no mailbox required.
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
            component={RouterLink}
            to="/reviews"
            variant="contained"
            size="large"
            startIcon={<FileText size={18} />}
          >
            Go to your reviews
          </Button>
          <Typography sx={{ mt: 1.25, fontSize: 13, color: 'text.disabled' }}>
            Activity on a review is what fills this inbox.
          </Typography>
        </Box>
      </Stack>
    </Paper>
  );
}

/**
 * The other kind of empty: the inbox has messages, the unread facet does not.
 *
 * That is a <em>win</em>, not a cold start, so it gets a compact trophy moment
 * rather than the full launchpad — celebrating it at the same scale as "you
 * have never received anything" would be shouting about a chore.
 */
export function AllCaughtUpState() {
  return (
    <Stack
      sx={{ alignItems: 'center', textAlign: 'center', px: 3, py: { xs: 6, sm: 8 } }}
      spacing={1.25}
    >
      <Box
        sx={{
          ...rise(60),
          width: 56,
          height: 56,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          borderRadius: '50%',
          color: 'success.main',
          bgcolor: (t) => t.qnop.surface2,
          border: 1,
          borderColor: 'divider',
          mb: 0.5,
        }}
      >
        <PartyPopper size={24} />
      </Box>
      <Typography component="h2" sx={{ ...rise(140), fontSize: 19, fontWeight: 800 }}>
        Inbox zero
      </Typography>
      <Typography
        sx={{ ...rise(200), fontSize: 14, color: 'text.secondary', maxWidth: 380, lineHeight: 1.6 }}
      >
        You have read everything. New activity on your reviews will show up the moment it happens.
      </Typography>
    </Stack>
  );
}
