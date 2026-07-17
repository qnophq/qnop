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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ArrowRight, UsersRound } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { MyTeam } from '../../api/generated';
import { useMyTeams } from '../../api/hooks/useMyTeams';
import { TeamRoleBadge } from '../../components/admin/teams/TeamRoleBadge';
import { PageHeader } from '../../components/admin/layout/PageHeader';

/**
 * The team-lead landing surface (issue #470): the teams the caller belongs to,
 * split into the teams they lead — interactive cards that open member management —
 * and the teams where they are only a member (informational). A LEAD reaches this
 * without being a global admin; the admin team console stays separate.
 */
export function MyTeamsPage() {
  const { data, isLoading, isError } = useMyTeams();

  if (isError) {
    return <Alert severity="error">Your teams could not be loaded.</Alert>;
  }
  if (isLoading || !data) {
    return <Typography color="text.secondary">Loading…</Typography>;
  }

  const led = data.items.filter((t) => t.teamRole === 'LEAD');
  const member = data.items.filter((t) => t.teamRole === 'MEMBER');

  return (
    <Stack spacing={4}>
      <PageHeader
        title="My Teams"
        description="Manage the teams you lead — add or remove members and hand over the lead role."
      />

      <Box component="section">
        <SectionLabel>{led.length === 1 ? 'Team you lead' : 'Teams you lead'}</SectionLabel>
        {led.length === 0 ? (
          <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
            <Typography color="text.secondary">You don’t lead any team yet.</Typography>
          </Paper>
        ) : (
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(auto-fill, minmax(280px, 1fr))' },
              gap: 2,
            }}
          >
            {led.map((team) => (
              <LedTeamCard key={team.teamId} team={team} />
            ))}
          </Box>
        )}
      </Box>

      {member.length > 0 && (
        <Box component="section">
          <SectionLabel>Also a member of</SectionLabel>
          <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
            <Stack divider={<Box sx={{ borderTop: 1, borderColor: 'divider' }} />}>
              {member.map((team) => (
                <Stack
                  key={team.teamId}
                  direction="row"
                  spacing={1.5}
                  sx={{ alignItems: 'center', px: 2, py: 1.5 }}
                >
                  <Box sx={{ color: 'text.disabled', display: 'flex' }}>
                    <UsersRound size={18} />
                  </Box>
                  <Typography sx={{ flex: 1, fontWeight: 500 }} noWrap>
                    {team.name}
                  </Typography>
                  <TeamRoleBadge role={team.teamRole} />
                </Stack>
              ))}
            </Stack>
          </Paper>
        </Box>
      )}
    </Stack>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
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

function LedTeamCard({ team }: { team: MyTeam }) {
  return (
    <Paper
      variant="outlined"
      component={RouterLink}
      to={`/my-teams/${team.teamId}`}
      aria-label={`Manage ${team.name}`}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        gap: 2,
        p: 2.5,
        textDecoration: 'none',
        color: 'inherit',
        transition: (t) =>
          t.transitions.create(['border-color', 'box-shadow', 'transform'], { duration: 150 }),
        '&:hover': {
          borderColor: 'primary.main',
          boxShadow: 3,
          transform: 'translateY(-2px)',
        },
        '&:focus-visible': {
          outline: 'none',
          borderColor: 'primary.main',
          boxShadow: (t) => t.qnop.focusRing,
        },
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            width: 40,
            height: 40,
            borderRadius: 1.75,
            bgcolor: 'primary.light',
            color: 'primary.main',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <UsersRound size={20} />
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontWeight: 700, lineHeight: 1.3 }} noWrap>
            {team.name}
          </Typography>
          <Box sx={{ mt: 0.5 }}>
            <TeamRoleBadge role={team.teamRole} />
          </Box>
        </Box>
      </Stack>
      <Stack
        direction="row"
        spacing={0.5}
        sx={{ alignItems: 'center', color: 'primary.main', fontWeight: 600, fontSize: 13.5 }}
      >
        <span>Manage members</span>
        <ArrowRight size={16} />
      </Stack>
    </Paper>
  );
}
