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

import type { FocusEvent, KeyboardEvent, MouseEvent, ReactNode } from 'react';
import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import type { SxProps, Theme } from '@mui/material/styles';
import { alpha, useTheme } from '@mui/material/styles';
import { Link as RouterLink } from 'react-router-dom';
import { CalendarDays, Crown, Users } from 'lucide-react';
import type { PublicUserProfile } from '../../api/generated';
import { useUserProfile } from '../../api/hooks/useUsers';
import { useAuthStore } from '../../stores/authStore';
import { UserAvatar } from '../shell/UserAvatar';

/** Hover intent: the card appears only after the pointer settles (issue #482). */
const SHOW_DELAY_MS = 320;

const CARD_WIDTH = 296;
const AVATAR_SIZE = 44;

const SINCE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

/** The compact scoreboard slice — the same numbers language as `/users/:slug`. */
const STAT_CELLS: { key: keyof PublicUserProfile['stats']; label: string }[] = [
  { key: 'reviewsOwned', label: 'Reviews' },
  { key: 'annotationsRaised', label: 'Annotations' },
  { key: 'commentsWritten', label: 'Comments' },
];

interface UserHoverCardProps {
  /**
   * The person's REAL user id. Callers must pass null for pseudonymised
   * identities (see {@link realAuthorId}) — the trigger then renders neither
   * a card nor a link, so anonymity cannot leak through a hover or a click.
   * Your own id shows your own card (consistent rows read as working rows)
   * and links to /profile.
   */
  userId: string | null | undefined;
  /**
   * The person's profile slug (issue #486) — preferred over the id for the
   * link target, so rendered hrefs read /users/anna-krause. Absent under the
   * same anonymity rule as the id.
   */
  slug?: string | null;
  children: ReactNode;
  /**
   * Clicking the trigger navigates to the person's profile (default). Opt
   * out when the caller renders its own link — nested anchors are invalid.
   */
  link?: boolean;
  /** Accessible name for the link, e.g. when the trigger is a decorative avatar. */
  profileName?: string;
  /** Layout overrides for the trigger element (e.g. flex sizing inside rows). */
  sx?: SxProps<Theme>;
}

/**
 * The person behind a name, without leaving the page (issue #482): hovering
 * or focusing the wrapped trigger shows a compact player card — identity
 * band, ring avatar, tenure, team chips and the contribution scoreboard in
 * miniature, speaking exactly the `/users/:slug` page's language (#473).
 * Modeled on {@link AnnotationHoverCard}: hover-intent delay, cache warmed
 * the moment the pointer arrives, pointer events pass through — a pure
 * preview that never traps focus. Unless opted out, the trigger doubles as
 * the link to the full profile (`/profile` for yourself).
 */
export function UserHoverCard({
  userId,
  slug,
  children,
  link = true,
  profileName,
  sx,
}: UserHoverCardProps) {
  const selfId = useAuthStore((s) => s.userId);

  if (!userId) {
    return children;
  }
  // Yourself included: a roster where one row silently ignores the hover
  // reads as broken, and your own card exposes nothing new. Only the click
  // target differs — your own profile lives at /profile.
  const to = !link ? undefined : userId === selfId ? '/profile' : `/users/${slug ?? userId}`;
  return (
    <HoverCard userId={userId} to={to} profileName={profileName} sx={sx}>
      {children}
    </HoverCard>
  );
}

interface TriggerFrameProps {
  children: ReactNode;
  /** Renders a RouterLink to this profile path; a plain span when absent. */
  to?: string;
  profileName?: string;
  sx?: SxProps<Theme>;
  onMouseEnter?: (event: MouseEvent<HTMLElement>) => void;
  onMouseLeave?: () => void;
  onFocus?: (event: FocusEvent<HTMLElement>) => void;
  onBlur?: () => void;
  onKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
}

/** The hoverable trigger shell — a profile link when `to` is set, else a span. */
function TriggerFrame({ children, to, profileName, sx, ...events }: TriggerFrameProps) {
  return (
    <Box
      component={to ? RouterLink : 'span'}
      {...(to
        ? {
            to,
            // Rows and cards underneath often have their own click targets
            // (open review, expand annotation) — the profile click wins.
            onClick: (event: MouseEvent) => event.stopPropagation(),
            'aria-label': profileName ? `View ${profileName}'s profile` : undefined,
          }
        : {})}
      sx={{
        display: 'inline-flex',
        minWidth: 0,
        maxWidth: '100%',
        textDecoration: 'none',
        color: 'inherit',
        ...sx,
      }}
      {...events}
    >
      {children}
    </Box>
  );
}

function HoverCard({
  userId,
  to,
  children,
  profileName,
  sx,
}: {
  userId: string;
  /** The profile link target; undefined when the caller renders its own anchor. */
  to?: string;
  children: ReactNode;
  profileName?: string;
  sx?: SxProps<Theme>;
}) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!anchorEl) {
      return;
    }
    const timer = setTimeout(() => setOpen(true), SHOW_DELAY_MS);
    return () => clearTimeout(timer);
  }, [anchorEl]);

  const show = (target: HTMLElement) => setAnchorEl(target);
  const hide = () => {
    setAnchorEl(null);
    setOpen(false);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      hide();
    }
  };

  return (
    <>
      <TriggerFrame
        to={to}
        profileName={profileName}
        sx={sx}
        onMouseEnter={(event) => show(event.currentTarget)}
        onMouseLeave={hide}
        onFocus={(event) => show(event.currentTarget)}
        onBlur={hide}
        onKeyDown={onKeyDown}
      >
        {children}
      </TriggerFrame>
      {/* Mounted only once hovered: a list of fifty people carries no query
          instances until the pointer actually arrives somewhere. */}
      {anchorEl && <HoverCardPopover userId={userId} anchorEl={anchorEl} open={open} />}
    </>
  );
}

function HoverCardPopover({
  userId,
  anchorEl,
  open,
}: {
  userId: string;
  anchorEl: HTMLElement;
  open: boolean;
}) {
  const theme = useTheme();
  // Mounting IS the hover: the cache warms during the intent delay.
  const profileQuery = useUserProfile(userId);
  const profile = profileQuery.data;
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  return (
    // A Popper, deliberately NOT a Popover: the card is a pure, non-modal
    // preview — no focus management, no aria-hidden on the rest of the app,
    // no scroll lock. Pointer events pass through entirely.
    <Popper
      open={open && !profileQuery.isError}
      anchorEl={anchorEl}
      placement="bottom-start"
      sx={{ pointerEvents: 'none', zIndex: theme.zIndex.tooltip }}
    >
      <Paper
        data-testid="user-hover-card"
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
            {/* The identity band — the profile hero in miniature (#473). */}
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
                    borderRadius: '50%',
                    p: '2px',
                    bgcolor: 'background.paper',
                    border: `2px solid ${alpha(blue, 0.45)}`,
                    flexShrink: 0,
                  }}
                >
                  <UserAvatar
                    name={profile.displayName}
                    size={AVATAR_SIZE}
                    imageUrl={profile.avatarUrl ?? null}
                  />
                </Box>
                <Box sx={{ minWidth: 0, pb: 0.25 }}>
                  <Typography noWrap sx={{ fontWeight: 800, fontSize: '0.95rem', lineHeight: 1.3 }}>
                    {profile.displayName}
                  </Typography>
                  <Stack
                    direction="row"
                    spacing={0.5}
                    sx={{ alignItems: 'center', color: 'text.secondary' }}
                  >
                    <CalendarDays size={11} aria-hidden />
                    <Typography variant="caption" noWrap>
                      Member since {SINCE_FORMAT.format(new Date(profile.createdAt))}
                    </Typography>
                  </Stack>
                </Box>
              </Stack>

              {profile.teams.length > 0 && (
                <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
                  {profile.teams.slice(0, 2).map((team) => {
                    const lead = team.role === 'LEAD';
                    return (
                      <Stack
                        key={team.id}
                        direction="row"
                        spacing={0.5}
                        sx={{
                          alignItems: 'center',
                          px: 0.75,
                          py: 0.25,
                          borderRadius: '999px',
                          border: `1px solid ${
                            lead ? alpha(theme.palette.warning.main, 0.5) : theme.palette.divider
                          }`,
                          ...(lead && {
                            bgcolor: alpha(theme.palette.warning.main, dark ? 0.14 : 0.08),
                          }),
                        }}
                      >
                        {lead ? (
                          <Crown
                            size={10}
                            aria-hidden
                            style={{ color: theme.palette.warning.main }}
                          />
                        ) : (
                          <Users size={10} aria-hidden />
                        )}
                        <Typography variant="caption" noWrap sx={{ fontWeight: 500 }}>
                          {team.name}
                        </Typography>
                      </Stack>
                    );
                  })}
                  {profile.teams.length > 2 && (
                    <Tooltip
                      title={profile.teams
                        .slice(2)
                        .map((team) => team.name)
                        .join(', ')}
                    >
                      <Typography variant="caption" color="text.secondary" sx={{ pt: 0.25 }}>
                        +{profile.teams.length - 2}
                      </Typography>
                    </Tooltip>
                  )}
                </Stack>
              )}

              {/* The scoreboard in miniature. */}
              <Stack
                direction="row"
                sx={{
                  mt: 1.25,
                  pt: 1,
                  borderTop: `1px solid ${theme.palette.divider}`,
                  '& > *': { flex: 1, minWidth: 0 },
                }}
              >
                {STAT_CELLS.map(({ key, label }) => {
                  const value = profile.stats[key];
                  return (
                    <Box key={key}>
                      <Typography
                        sx={{
                          fontWeight: 800,
                          fontSize: '0.95rem',
                          lineHeight: 1.2,
                          fontVariantNumeric: 'tabular-nums',
                          color: value > 0 ? blue : 'text.disabled',
                        }}
                      >
                        {value}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" noWrap component="p">
                        {label}
                      </Typography>
                    </Box>
                  );
                })}
              </Stack>
            </Box>
          </Box>
        ) : (
          <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', p: 1.75 }}>
            <Skeleton variant="circular" width={AVATAR_SIZE} height={AVATAR_SIZE} />
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
