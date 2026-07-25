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
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { SxProps, Theme } from '@mui/material/styles';
import { alpha, useTheme } from '@mui/material/styles';
import { CalendarDays, Users } from 'lucide-react';
import { useTeamProfile } from '../../api/hooks/useTeamProfile';
import { TeamAvatar } from '../shell/TeamAvatar';
import { HoverCardShell } from './UserHoverCard';

const CARD_WIDTH = 296;
const CREST_SIZE = 44;

const SINCE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

interface TeamHoverCardProps {
  /**
   * The team's REAL id. Callers must pass null for anonymised roster rows
   * (issue #422, synthetic tokens) — the trigger then renders neither a card
   * nor a link, so anonymity cannot leak through a hover or a click, exactly
   * like anonymised users.
   */
  teamId: string | null | undefined;
  /** The team slug (issue #470) — preferred over the id for the link target. */
  slug?: string | null;
  children: ReactNode;
  /** Opt out when the caller renders its own link — nested anchors are invalid. */
  link?: boolean;
  /** Accessible name for the link, e.g. when the trigger is a decorative crest. */
  profileName?: string;
  /** Layout overrides for the trigger element. */
  sx?: SxProps<Theme>;
}

/**
 * The team behind a crest, without leaving the page (issue #586): hovering or
 * focusing the wrapped trigger shows the team card in miniature — ring crest,
 * name, tenure, description teaser and the member count when the team exposes
 * its roster. The team-scale twin of {@link UserHoverCard}, sharing its
 * hover-intent shell; unless opted out, the trigger doubles as the link to
 * `/teams/{slug}`.
 */
export function TeamHoverCard({
  teamId,
  slug,
  children,
  link = true,
  profileName,
  sx,
}: TeamHoverCardProps) {
  if (!teamId) {
    return children;
  }
  const to = !link ? undefined : `/teams/${slug ?? teamId}`;
  return (
    <HoverCardShell
      to={to}
      profileName={profileName}
      sx={sx}
      popover={(anchorEl, open) => (
        <TeamHoverCardPopover teamId={teamId} anchorEl={anchorEl} open={open} />
      )}
    >
      {children}
    </HoverCardShell>
  );
}

function TeamHoverCardPopover({
  teamId,
  anchorEl,
  open,
}: {
  teamId: string;
  anchorEl: HTMLElement;
  open: boolean;
}) {
  const theme = useTheme();
  // Mounting IS the hover: the cache warms during the intent delay.
  const profileQuery = useTeamProfile(teamId);
  const profile = profileQuery.data;
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  return (
    <Popper
      open={open && !profileQuery.isError}
      anchorEl={anchorEl}
      placement="bottom-start"
      sx={{ pointerEvents: 'none', zIndex: theme.zIndex.tooltip }}
    >
      <Paper
        data-testid="team-hover-card"
        sx={{
          mt: 0.75,
          width: CARD_WIDTH,
          borderRadius: '12px',
          border: `1px solid ${theme.palette.divider}`,
          boxShadow:
            theme.palette.mode === 'light' ? '0 12px 32px -8px rgba(1, 32, 66, 0.25)' : 'none',
          overflow: 'hidden',
        }}
      >
        {profile ? (
          <Box>
            {/* The identity band — the team profile hero in miniature. */}
            <Box
              aria-hidden
              sx={{
                height: 44,
                background: `
                  radial-gradient(70% 160% at 80% 0%, ${alpha(blue, dark ? 0.3 : 0.18)} 0%, transparent 100%),
                  linear-gradient(120deg, ${alpha(blue, dark ? 0.2 : 0.11)}, ${alpha(blue, 0.03)})
                `,
              }}
            />
            <Box sx={{ px: 1.75, pb: 1.5 }}>
              <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-end', mt: -2.5 }}>
                <Box
                  sx={{
                    display: 'inline-flex',
                    // Concentric with the crest's 30% radius — never a circle
                    // around the rounded-square team identity.
                    borderRadius: `${Math.round(CREST_SIZE * 0.3) + 4}px`,
                    p: '2px',
                    bgcolor: 'background.paper',
                    border: `2px solid ${alpha(blue, 0.45)}`,
                    flexShrink: 0,
                  }}
                >
                  <TeamAvatar
                    name={profile.name}
                    size={CREST_SIZE}
                    imageUrl={profile.avatarUrl ?? null}
                  />
                </Box>
                <Box sx={{ minWidth: 0, pb: 0.25 }}>
                  <Typography noWrap sx={{ fontWeight: 800, fontSize: '0.95rem', lineHeight: 1.3 }}>
                    {profile.name}
                  </Typography>
                  <Stack
                    direction="row"
                    spacing={0.5}
                    sx={{ alignItems: 'center', color: 'text.secondary' }}
                  >
                    <CalendarDays size={11} aria-hidden />
                    <Typography variant="caption" noWrap>
                      Team since {SINCE_FORMAT.format(new Date(profile.createdAt))}
                    </Typography>
                  </Stack>
                </Box>
              </Stack>

              {profile.description && (
                <Typography
                  variant="caption"
                  sx={{
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                    color: 'text.secondary',
                    mt: 1,
                  }}
                >
                  {profile.description}
                </Typography>
              )}

              {/* Member count only when the team exposes its roster (issue #586). */}
              {profile.members != null && (
                <Stack
                  direction="row"
                  spacing={0.5}
                  sx={{ alignItems: 'center', color: 'text.secondary', mt: 1 }}
                >
                  <Users size={11} aria-hidden />
                  <Typography variant="caption">
                    {profile.members.length} {profile.members.length === 1 ? 'member' : 'members'}
                  </Typography>
                </Stack>
              )}
            </Box>
          </Box>
        ) : (
          <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', p: 1.75 }}>
            <Skeleton variant="circular" width={CREST_SIZE} height={CREST_SIZE} />
            <Box sx={{ flex: 1 }}>
              <Skeleton width="70%" />
              <Skeleton width="45%" />
            </Box>
          </Stack>
        )}
      </Paper>
    </Popper>
  );
}
