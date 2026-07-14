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

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import {
  CalendarDays,
  CheckCircle2,
  Crown,
  FileText,
  MessagesSquare,
  NotebookPen,
  UserCheck,
  Users,
} from 'lucide-react';
import { useEffect } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import type { PublicUserProfile } from '../api/generated';
import { userKeys, useUserProfile } from '../api/hooks/useUsers';
import { useAuthStore } from '../stores/authStore';
import { AchievementRow } from '../components/profile/AchievementRow';
import { publicProfileAchievements } from '../components/profile/profileModel';
import { UserAvatar } from '../components/shell/UserAvatar';

const SINCE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

const STAT_TILES: { key: keyof PublicUserProfile['stats']; label: string; icon: LucideIcon }[] = [
  { key: 'reviewsOwned', label: 'Reviews owned', icon: FileText },
  { key: 'reviewsParticipating', label: 'Reviewing', icon: UserCheck },
  { key: 'annotationsRaised', label: 'Annotations raised', icon: NotebookPen },
  { key: 'annotationsResolved', label: 'Resolved', icon: CheckCircle2 },
  { key: 'commentsWritten', label: 'Comments', icon: MessagesSquare },
];

const fadeUp = {
  '@keyframes publicProfileFadeUp': {
    from: { opacity: 0, transform: 'translateY(10px)' },
    to: { opacity: 1, transform: 'translateY(0)' },
  },
};

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A colleague's workspace-public profile (issues #454, #473): the campaign's
 * player-card language — identity hero with team affiliations, the
 * contribution scoreboard and the achievement stickers, all fed by the
 * server's ADR-0038-safe aggregates. Your own profile redirects to `/profile`.
 *
 * The `/users/:userId` segment accepts an id OR the profile slug (issue
 * #486, the ReviewParamGate convention): UUID-shaped segments resolve by id,
 * anything else via the by-slug endpoint — and an id visit is canonicalised
 * to the pretty slug URL once the profile is known.
 */
export function UserProfilePage() {
  const theme = useTheme();
  const { userId: segment = '' } = useParams<{ userId: string }>();
  const isId = UUID_SHAPE.test(segment);
  const selfId = useAuthStore((s) => s.userId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Shared with the hover card (issue #482): one cache entry per person, so
  // hover→page (and the canonicalisation below) stay fetch-free.
  const profileQuery = useUserProfile(segment, segment !== selfId);

  // Canonicalise /users/<uuid> to /users/<slug>: seed the slug's cache entry
  // first so the replace renders instantly instead of refetching.
  const loadedSlug = profileQuery.data?.slug;
  useEffect(() => {
    if (isId && loadedSlug && profileQuery.data) {
      queryClient.setQueryData(userKeys.publicProfile(loadedSlug), profileQuery.data);
      navigate(`/users/${loadedSlug}`, { replace: true });
    }
  }, [isId, loadedSlug, profileQuery.data, queryClient, navigate]);

  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';
  const stagger = (index: number) => ({
    ...fadeUp,
    animation: `publicProfileFadeUp 0.45s ${theme.transitions.easing.easeOut} both`,
    animationDelay: `${index * 90}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  });

  // Own profile: the id short-circuits before fetching; a slug visit is
  // recognised once the payload's id matches.
  if ((segment !== '' && segment === selfId) || profileQuery.data?.id === selfId) {
    return <Navigate to="/profile" replace />;
  }
  if (profileQuery.isPending) {
    return (
      <Stack spacing={2.5}>
        <Skeleton variant="rounded" height={210} />
        <Skeleton variant="rounded" height={92} />
        <Skeleton variant="rounded" height={130} />
      </Stack>
    );
  }
  if (profileQuery.isError || !profileQuery.data) {
    return <Alert severity="error">This user does not exist.</Alert>;
  }

  const profile = profileQuery.data;
  const achievements = publicProfileAchievements(profile.stats);
  const earnedCount = achievements.filter((a) => a.earned).length;

  return (
    <Stack spacing={2.5} data-testid="public-profile">
      {/* Identity hero: the player card a colleague sees. */}
      <Paper
        variant="outlined"
        sx={{
          ...stagger(0),
          overflow: 'hidden',
          borderRadius: '16px',
        }}
      >
        <Box
          aria-hidden
          sx={{
            height: 96,
            background: `
              radial-gradient(60% 150% at 80% 0%, ${alpha(blue, dark ? 0.28 : 0.16)} 0%, transparent 100%),
              linear-gradient(120deg, ${alpha(blue, dark ? 0.18 : 0.1)}, ${alpha(blue, 0.03)})
            `,
          }}
        />
        <Box sx={{ px: { xs: 2.5, sm: 3 }, pb: 2.5 }}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ alignItems: { sm: 'flex-end' }, mt: -5 }}
          >
            <Box
              sx={{
                display: 'inline-flex',
                borderRadius: '50%',
                p: '3px',
                bgcolor: 'background.paper',
                border: `2px solid ${alpha(blue, 0.45)}`,
                width: 'fit-content',
              }}
            >
              <UserAvatar
                name={profile.displayName}
                size={84}
                imageUrl={profile.avatarUrl ?? null}
              />
            </Box>
            <Box sx={{ minWidth: 0, pb: 0.5 }}>
              <Typography variant="h2" sx={{ fontSize: '1.5rem', lineHeight: 1.2 }}>
                {profile.displayName}
              </Typography>
              <Stack
                direction="row"
                spacing={0.75}
                sx={{ alignItems: 'center', color: 'text.secondary', mt: 0.5 }}
              >
                <CalendarDays size={14} aria-hidden />
                <Typography variant="body2">
                  Member since {SINCE_FORMAT.format(new Date(profile.createdAt))}
                </Typography>
              </Stack>
            </Box>
          </Stack>

          {profile.teams.length > 0 && (
            <Stack
              direction="row"
              spacing={1}
              useFlexGap
              sx={{ flexWrap: 'wrap', mt: 2 }}
              data-testid="profile-teams"
            >
              {profile.teams.map((team) => {
                const lead = team.role === 'LEAD';
                return (
                  <Tooltip
                    key={team.id}
                    title={lead ? `Leads ${team.name}` : `Member of ${team.name}`}
                  >
                    <Chip
                      size="small"
                      icon={
                        lead ? (
                          <Crown size={13} style={{ color: theme.palette.warning.main }} />
                        ) : (
                          <Users size={13} />
                        )
                      }
                      label={team.name}
                      sx={{
                        fontWeight: 500,
                        ...(lead && {
                          borderColor: alpha(theme.palette.warning.main, 0.5),
                          bgcolor: alpha(theme.palette.warning.main, dark ? 0.14 : 0.08),
                        }),
                      }}
                      variant="outlined"
                    />
                  </Tooltip>
                );
              })}
            </Stack>
          )}
        </Box>
      </Paper>

      {/* The contribution scoreboard — ADR-0038-safe aggregates. */}
      <Box
        sx={{
          ...stagger(1),
          display: 'grid',
          gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(5, 1fr)' },
          gap: 1.5,
        }}
      >
        {STAT_TILES.map(({ key, label, icon: Icon }) => {
          const value = profile.stats[key];
          return (
            <Paper key={key} variant="outlined" sx={{ px: 2, py: 1.5, borderRadius: '12px' }}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
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
                    color: value > 0 ? blue : 'text.secondary',
                    bgcolor: alpha(
                      value > 0 ? blue : theme.palette.text.secondary,
                      dark ? 0.16 : 0.1,
                    ),
                  }}
                >
                  <Icon size={16} />
                </Box>
                <Box sx={{ minWidth: 0 }}>
                  <Typography
                    sx={{
                      fontSize: '1.4rem',
                      fontWeight: 800,
                      lineHeight: 1.2,
                      fontVariantNumeric: 'tabular-nums',
                      color: value > 0 ? 'text.primary' : 'text.disabled',
                    }}
                  >
                    {value}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap component="p">
                    {label}
                  </Typography>
                </Box>
              </Stack>
            </Paper>
          );
        })}
      </Box>

      {/* Achievements — the same sticker language as the own player card. */}
      <Paper variant="outlined" sx={{ ...stagger(2), p: { xs: 2.5, sm: 3 }, borderRadius: '16px' }}>
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 1.5 }}
        >
          <Typography sx={{ fontSize: 14, fontWeight: 700 }}>Achievements</Typography>
          <Typography variant="caption" color="text.secondary">
            {earnedCount} of {achievements.length} earned
          </Typography>
        </Stack>
        <AchievementRow achievements={achievements} />
      </Paper>
    </Stack>
  );
}
