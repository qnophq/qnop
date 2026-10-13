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
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { keyframes } from '@mui/material/styles';
import { ArrowRight, Crown, Eye, Lock, Trophy, UsersRound } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router';
import type { MyTeam } from '../../api/generated';
import { useMyTeams } from '../../api/hooks/useMyTeams';
import { TeamRoleBadge } from '../../components/admin/teams/TeamRoleBadge';
import { TeamAvatar } from '../../components/shell/TeamAvatar';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { MyTeamsEmptyState } from './MyTeamsEmptyState';
import {
  computeAchievements,
  leadershipStats,
  teamTier,
  type Achievement,
} from '../../utils/teamProgress';

const revealUp = keyframes`
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
`;

/** A staggered fade-up entrance, disabled under reduced-motion. */
function reveal(index: number) {
  return {
    animation: `${revealUp} 420ms cubic-bezier(0.16, 1, 0.3, 1) both`,
    animationDelay: `${index * 55}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  } as const;
}

/**
 * The "My Teams" surface (issue #470), open to every user. A "Leadership HQ"
 * banner (rank, stats, achievements) crowns the teams the caller leads — shown as
 * guild cards with a crest, roster tier and progress that open member management.
 * Below, the teams they are only a member of open a read-only roster. Purely
 * derived motivation over the real team data — no invented metrics.
 */
export function MyTeamsPage() {
  const { t } = useTranslation(['teams', 'common']);
  const { data, isLoading, isError } = useMyTeams();

  if (isError) {
    return <Alert severity="error">{t('myTeams.loadError')}</Alert>;
  }
  if (isLoading || !data) {
    return <Typography color="text.secondary">{t('common:loading')}</Typography>;
  }

  const led = data.items.filter((team) => team.teamRole === 'LEAD');
  const member = data.items.filter((team) => team.teamRole === 'MEMBER');
  const stats = leadershipStats(led);
  const achievements = computeAchievements(led);

  return (
    <Stack spacing={4}>
      <PageHeader title={t('myTeams.title')} description={t('myTeams.description')} />

      {data.items.length === 0 && <MyTeamsEmptyState />}

      {led.length > 0 && <LeadershipHero stats={stats} achievements={achievements} />}

      {led.length > 0 && (
        <TeamSection
          label={t('myTeams.sections.led', { count: led.length })}
          teams={led}
          canManage
          startIndex={1}
        />
      )}

      {member.length > 0 && (
        <TeamSection
          label={t('myTeams.sections.member', { count: member.length })}
          teams={member}
          canManage={false}
          startIndex={led.length + 1}
        />
      )}
    </Stack>
  );
}

function TeamSection({
  label,
  teams,
  canManage,
  startIndex,
}: {
  label: string;
  teams: MyTeam[];
  canManage: boolean;
  startIndex: number;
}) {
  return (
    <Box component="section">
      <SectionLabel>{label}</SectionLabel>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(auto-fill, minmax(300px, 1fr))' },
          gap: 2,
        }}
      >
        {teams.map((team, i) => (
          <TeamCard key={team.teamId} team={team} index={startIndex + i} canManage={canManage} />
        ))}
      </Box>
    </Box>
  );
}

/** The My Teams detail path — the slug when one exists, the id otherwise (issue #470). */
function teamPath(team: MyTeam): string {
  return `/my-teams/${team.slug ?? team.teamId}`;
}

function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <Typography
      component="h2"
      sx={{
        fontSize: 12,
        fontWeight: 600,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color: 'text.disabled',
        mb: 1.5,
      }}
    >
      {children}
    </Typography>
  );
}

function LeadershipHero({
  stats,
  achievements,
}: {
  stats: ReturnType<typeof leadershipStats>;
  achievements: Achievement[];
}) {
  const { t } = useTranslation('teams');
  return (
    <Paper
      variant="outlined"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        p: { xs: 2.5, sm: 3 },
        // A quiet brand-blue glow in the top-right gives atmosphere in both themes.
        backgroundImage:
          'radial-gradient(120% 140% at 100% 0%, rgba(18,144,239,0.10), transparent 60%)',
        ...reveal(0),
      }}
    >
      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={{ xs: 2.5, md: 3 }}
        sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}
      >
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center', minWidth: 0 }}>
          <Box
            aria-hidden
            sx={{
              width: 54,
              height: 54,
              borderRadius: 2.5,
              flexShrink: 0,
              display: 'grid',
              placeItems: 'center',
              color: 'primary.main',
              bgcolor: 'primary.light',
              boxShadow: (theme) => `inset 0 0 0 1px ${theme.palette.primary.main}22`,
            }}
          >
            <Crown size={26} strokeWidth={2} />
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography
              sx={{
                fontSize: 11,
                fontWeight: 600,
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                color: 'text.disabled',
              }}
            >
              {t('myTeams.hero.eyebrow')}
            </Typography>
            <Typography sx={{ fontSize: 22, fontWeight: 800, lineHeight: 1.2 }} noWrap>
              {t(stats.rankKey)}
            </Typography>
            <Typography sx={{ fontSize: 14, color: 'text.secondary', mt: 0.25 }}>
              {t('myTeams.hero.summary', {
                teams: t('myTeams.hero.teams', { count: stats.teamsLed }),
                teammates: t('myTeams.hero.teammates', { count: stats.totalTeammates }),
              })}
            </Typography>
          </Box>
        </Stack>

        <Stack direction="row" spacing={1.5} sx={{ flexShrink: 0 }}>
          <StatTile value={stats.teamsLed} label={t('myTeams.hero.stats.teamsLed')} />
          <StatTile value={stats.totalTeammates} label={t('myTeams.hero.stats.teammates')} />
          <StatTile value={stats.largestTeam} label={t('myTeams.hero.stats.largestTeam')} />
        </Stack>
      </Stack>

      <Box
        sx={{
          mt: 2.5,
          pt: 2.5,
          borderTop: 1,
          borderColor: 'divider',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 1,
        }}
      >
        {achievements.map((achievement) => (
          <AchievementPill key={achievement.id} achievement={achievement} />
        ))}
      </Box>
    </Paper>
  );
}

function StatTile({ value, label }: { value: number; label: string }) {
  return (
    <Box
      sx={{
        minWidth: 82,
        px: 1.75,
        py: 1.25,
        borderRadius: 2,
        textAlign: 'center',
        bgcolor: (theme) => theme.qnop.surface2,
        border: 1,
        borderColor: 'divider',
      }}
    >
      <Typography sx={{ fontSize: 24, fontWeight: 800, lineHeight: 1.1 }}>{value}</Typography>
      <Typography
        sx={{
          fontSize: 10.5,
          fontWeight: 600,
          letterSpacing: '0.05em',
          textTransform: 'uppercase',
          color: 'text.disabled',
          mt: 0.25,
        }}
      >
        {label}
      </Typography>
    </Box>
  );
}

function AchievementPill({ achievement }: { achievement: Achievement }) {
  const { t } = useTranslation('teams');
  const { earned, labelKey, hintKey, hintValues } = achievement;
  const hint = t(hintKey, hintValues);
  return (
    <Tooltip
      title={
        earned
          ? t('myTeams.achievements.unlocked', { hint })
          : t('myTeams.achievements.locked', { hint })
      }
    >
      <Box
        component="span"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.75,
          px: 1.25,
          py: 0.5,
          borderRadius: 999,
          fontSize: 12.5,
          fontWeight: 600,
          border: '1px solid',
          ...(earned
            ? {
                color: (theme) => theme.qnop.badge.amber.fg,
                bgcolor: (theme) => theme.qnop.badge.amber.bg,
                borderColor: (theme) => theme.qnop.badge.amber.border,
              }
            : {
                color: 'text.disabled',
                bgcolor: 'transparent',
                borderColor: 'divider',
                borderStyle: 'dashed',
              }),
        }}
      >
        {earned ? <Trophy size={13} /> : <Lock size={12} />}
        {t(labelKey)}
      </Box>
    </Tooltip>
  );
}

function TeamCard({ team, index, canManage }: { team: MyTeam; index: number; canManage: boolean }) {
  const { t } = useTranslation('teams');
  const tier = teamTier(team.memberCount);
  const tierName = t(tier.nameKey);
  const nextTierName = tier.nextFloor === null ? null : t(teamTier(tier.nextFloor).nameKey);
  const toNext = tier.nextFloor === null ? 0 : tier.nextFloor - team.memberCount;
  return (
    <Paper
      variant="outlined"
      component={RouterLink}
      to={teamPath(team)}
      aria-label={
        canManage
          ? t('myTeams.card.manageAria', { name: team.name })
          : t('myTeams.card.viewAria', { name: team.name })
      }
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 2,
        p: 2.5,
        textDecoration: 'none',
        color: 'inherit',
        transition: (theme) =>
          theme.transitions.create(['border-color', 'box-shadow', 'transform'], { duration: 150 }),
        '&:hover': {
          borderColor: 'primary.main',
          boxShadow: 3,
          transform: 'translateY(-2px)',
        },
        '&:focus-visible': {
          outline: 'none',
          borderColor: 'primary.main',
          boxShadow: (theme) => theme.qnop.focusRing,
        },
        ...reveal(index + 1),
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <TeamAvatar name={team.name} imageUrl={team.avatarUrl} size={44} />
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontWeight: 700, lineHeight: 1.3 }} noWrap>
            {team.name}
          </Typography>
          <Box sx={{ mt: 0.5 }}>
            <TeamRoleBadge role={team.teamRole} />
          </Box>
        </Box>
      </Stack>

      <Box>
        <Stack
          direction="row"
          sx={{ justifyContent: 'space-between', alignItems: 'baseline', mb: 0.75 }}
        >
          <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
            <Typography sx={{ fontWeight: 700, fontSize: 13.5 }}>{tierName}</Typography>
            <Typography sx={{ fontSize: 12.5, color: 'text.disabled' }}>·</Typography>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              {t('people.memberCount', { count: team.memberCount })}
            </Typography>
          </Stack>
          <Typography sx={{ fontSize: 11.5, color: 'text.disabled' }}>
            {nextTierName
              ? t('myTeams.card.toNextTier', { remaining: toNext, tier: nextTierName })
              : t('myTeams.card.topTier')}
          </Typography>
        </Stack>
        <LinearProgress
          variant="determinate"
          value={Math.round(tier.progress * 100)}
          aria-label={t('myTeams.card.tierProgress', { tier: tierName })}
          sx={{ height: 6, borderRadius: 999, bgcolor: (theme) => theme.qnop.surface2 }}
        />
      </Box>

      <Stack
        direction="row"
        spacing={0.5}
        sx={{ alignItems: 'center', color: 'primary.main', fontWeight: 600, fontSize: 13.5 }}
      >
        {canManage ? <UsersRound size={15} /> : <Eye size={15} />}
        <span>{canManage ? t('myTeams.card.manageMembers') : t('myTeams.card.viewMembers')}</span>
        <ArrowRight size={16} />
      </Stack>
    </Paper>
  );
}
