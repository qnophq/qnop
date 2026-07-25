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
import { CalendarDays, CheckCircle2, Crown, FileText, Lock, Users } from 'lucide-react';
import { useEffect } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import type { PublicTeamProfileMember, PublicTeamReview } from '../api/generated';
import { teamProfileKeys, useTeamProfile } from '../api/hooks/useTeamProfile';
import { ToneBadge } from '../components/admin/ToneBadge';
import { DocumentIcon } from '../components/reviews/list/ReviewListParts';
import { UserHoverCard } from '../components/people/UserHoverCard';
import {
  WORKFLOW_TONES,
  isOpenWorkflowState,
  workflowLabel,
} from '../components/reviews/workflowMeta';
import { TeamAvatar } from '../components/shell/TeamAvatar';
import { UserAvatar } from '../components/shell/UserAvatar';
import { avatarSrc } from '../utils/avatarUrl';
import { useFormatters } from '../hooks/useFormatters';

const SINCE_FORMAT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric' });

const fadeUp = {
  '@keyframes teamProfileFadeUp': {
    from: { opacity: 0, transform: 'translateY(10px)' },
    to: { opacity: 1, transform: 'translateY(0)' },
  },
};

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A quiet lock note for a section the team keeps private (issue #586). */
function PrivateNote({ text }: { text: string }) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: 'center', color: 'text.secondary', py: 0.5 }}
      data-testid="team-profile-private"
    >
      <Lock size={14} aria-hidden />
      <Typography variant="body2">{text}</Typography>
    </Stack>
  );
}

/**
 * A team's workspace-public profile (issue #586) — the team-scale player card,
 * speaking exactly the `/users/:slug` page's language: identity hero with the
 * ring crest, the scoreboard tiles, then the roster and the shared missions.
 * What the team hides from non-members (roster, review participation) arrives
 * hidden from the server and renders as a quiet lock note — the page never
 * decides visibility itself. `/teams/:teamId` accepts an id OR the team slug;
 * an id visit is canonicalised to the pretty slug URL once the team is known.
 */
export function TeamProfilePage() {
  const theme = useTheme();
  const { teamId: segment = '' } = useParams<{ teamId: string }>();
  const isId = UUID_SHAPE.test(segment);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { shortRelativeTime } = useFormatters();
  // Shared with the team hover card: one cache entry per team.
  const profileQuery = useTeamProfile(segment);

  // Canonicalise /teams/<uuid> to /teams/<slug>, seeding the slug cache first
  // so the replace renders instantly (the user profile page's recipe).
  const loadedSlug = profileQuery.data?.slug;
  useEffect(() => {
    if (isId && loadedSlug && profileQuery.data) {
      queryClient.setQueryData(teamProfileKeys.publicProfile(loadedSlug), profileQuery.data);
      navigate(`/teams/${loadedSlug}`, { replace: true });
    }
  }, [isId, loadedSlug, profileQuery.data, queryClient, navigate]);

  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';
  const stagger = (index: number) => ({
    ...fadeUp,
    animation: `teamProfileFadeUp 0.45s ${theme.transitions.easing.easeOut} both`,
    animationDelay: `${index * 90}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  });

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
    return <Alert severity="error">This team does not exist.</Alert>;
  }

  const profile = profileQuery.data;
  const members = profile.members ?? null;
  const reviews = profile.reviews ?? null;
  const activeReviews = reviews?.filter((r) => isOpenWorkflowState(r.workflowState)) ?? null;
  const completedCount =
    reviews === null ? null : reviews.filter((r) => r.workflowState === 'FINALIZED').length;

  // The scoreboard shows only what this caller may see — a hidden section
  // contributes no tile rather than a fake zero.
  const tiles: { label: string; value: number; icon: LucideIcon }[] = [];
  if (members !== null) {
    tiles.push({ label: 'Members', value: members.length, icon: Users });
  }
  if (activeReviews !== null && completedCount !== null) {
    tiles.push({ label: 'Active reviews', value: activeReviews.length, icon: FileText });
    tiles.push({ label: 'Completed', value: completedCount, icon: CheckCircle2 });
  }

  return (
    <Stack spacing={2.5} data-testid="team-profile">
      {/* Identity hero: the team-scale player card. */}
      <Paper variant="outlined" sx={{ ...stagger(0), overflow: 'hidden', borderRadius: '16px' }}>
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
              <TeamAvatar name={profile.name} size={84} imageUrl={profile.avatarUrl ?? null} />
            </Box>
            <Box sx={{ minWidth: 0, pb: 0.5 }}>
              <Typography variant="h2" sx={{ fontSize: '1.5rem', lineHeight: 1.2 }}>
                {profile.name}
              </Typography>
              <Stack
                direction="row"
                spacing={0.75}
                sx={{ alignItems: 'center', color: 'text.secondary', mt: 0.5 }}
              >
                <CalendarDays size={14} aria-hidden />
                <Typography variant="body2">
                  Team since {SINCE_FORMAT.format(new Date(profile.createdAt))}
                </Typography>
                {profile.viewerIsMember && (
                  <Chip
                    size="small"
                    label="Your team"
                    component={RouterLink}
                    to={`/my-teams/${profile.slug ?? profile.id}`}
                    clickable
                    sx={{ ml: 0.5, fontWeight: 500 }}
                    variant="outlined"
                  />
                )}
              </Stack>
            </Box>
          </Stack>
          {profile.description && (
            <Typography variant="body1" sx={{ mt: 2, color: 'text.secondary', maxWidth: '70ch' }}>
              {profile.description}
            </Typography>
          )}
        </Box>
      </Paper>

      {/* The scoreboard — only sections this caller may see contribute tiles. */}
      {tiles.length > 0 && (
        <Box
          sx={{
            ...stagger(1),
            display: 'grid',
            gridTemplateColumns: { xs: '1fr 1fr', sm: `repeat(${tiles.length}, 1fr)` },
            gap: 1.5,
          }}
        >
          {tiles.map(({ label, value, icon: Icon }) => (
            <Paper key={label} variant="outlined" sx={{ px: 2, py: 1.5, borderRadius: '12px' }}>
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
          ))}
        </Box>
      )}

      {/* The roster — or the quiet lock note when the team keeps it private. */}
      <Paper variant="outlined" sx={{ ...stagger(2), borderRadius: '16px', px: 2.5, py: 2 }}>
        <Typography variant="h3" sx={{ fontSize: '1rem', mb: 1.5 }}>
          Roster
        </Typography>
        {members === null ? (
          <PrivateNote text="This team keeps its roster private." />
        ) : members.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No members yet.
          </Typography>
        ) : (
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr' },
              gap: 1,
            }}
            data-testid="team-profile-roster"
          >
            {members.map((member: PublicTeamProfileMember) => {
              const lead = member.role === 'LEAD';
              return (
                <UserHoverCard
                  key={member.id}
                  userId={member.id}
                  slug={member.slug}
                  profileName={member.displayName}
                >
                  <Stack
                    direction="row"
                    spacing={1.25}
                    sx={{
                      alignItems: 'center',
                      px: 1.25,
                      py: 1,
                      borderRadius: '10px',
                      minWidth: 0,
                      width: '100%',
                      '&:hover': { bgcolor: theme.qnop.surface2 },
                    }}
                  >
                    <UserAvatar
                      name={member.displayName}
                      size={32}
                      imageUrl={member.avatarUrl ?? avatarSrc(member.id)}
                    />
                    <Typography noWrap sx={{ fontWeight: 500, fontSize: '0.9rem', flex: 1 }}>
                      {member.displayName}
                    </Typography>
                    {lead && (
                      <Tooltip title="Team lead">
                        <Crown
                          size={14}
                          aria-label="Team lead"
                          style={{ color: theme.palette.warning.main, flexShrink: 0 }}
                        />
                      </Tooltip>
                    )}
                  </Stack>
                </UserHoverCard>
              );
            })}
          </Box>
        )}
      </Paper>

      {/* The team's missions — reviews the CALLER may see (server-intersected). */}
      <Paper variant="outlined" sx={{ ...stagger(3), borderRadius: '16px', px: 2.5, py: 2 }}>
        <Typography variant="h3" sx={{ fontSize: '1rem', mb: 1.5 }}>
          Review missions
        </Typography>
        {reviews === null ? (
          <PrivateNote text="This team keeps its review activity private." />
        ) : reviews.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No reviews you can see — the list only shows reviews you have access to.
          </Typography>
        ) : (
          <Stack spacing={0.5} data-testid="team-profile-reviews">
            {reviews.map((review: PublicTeamReview) => (
              <Stack
                key={review.id}
                component={RouterLink}
                to={`/reviews/${review.slug ?? review.id}`}
                direction="row"
                spacing={1.5}
                sx={{
                  alignItems: 'center',
                  px: 1.25,
                  py: 1,
                  borderRadius: '10px',
                  textDecoration: 'none',
                  color: 'inherit',
                  '&:hover': { bgcolor: theme.qnop.surface2 },
                  '&:focus-visible': { outline: 'none', boxShadow: theme.qnop.focusRing },
                }}
              >
                {/* The document-type sheet, exactly as on /reviews (issue #509 follow-up). */}
                <DocumentIcon size={22} contentType={review.contentType} />
                <Typography noWrap sx={{ fontWeight: 500, fontSize: '0.9rem', flex: 1 }}>
                  {review.title}
                </Typography>
                <ToneBadge
                  tone={WORKFLOW_TONES[review.workflowState] ?? 'neutral'}
                  label={workflowLabel(review.workflowState)}
                />
                <Typography
                  variant="caption"
                  sx={{ color: 'text.disabled', flexShrink: 0, minWidth: 56, textAlign: 'right' }}
                >
                  {shortRelativeTime(review.updatedAt)}
                </Typography>
              </Stack>
            ))}
          </Stack>
        )}
      </Paper>
    </Stack>
  );
}
