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

import { useState, type MouseEvent } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { ArrowLeftRight, MoreVertical, UserMinus, UserPlus } from 'lucide-react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { TeamMember, TeamRole } from '../../api/generated';
import {
  useMyTeam,
  useRemoveMyTeamMember,
  useSetMyTeamMemberRole,
} from '../../api/hooks/useMyTeams';
import { AddMyTeamMemberDialog } from '../../components/my-teams/AddMyTeamMemberDialog';
import { TeamRoleBadge } from '../../components/admin/teams/TeamRoleBadge';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { PersonLink } from '../../components/dashboard/PersonLink';
import { useFormatters } from '../../hooks/useFormatters';
import { selectIsAdmin, useAuthStore } from '../../stores/authStore';
import { apiErrorMessage } from '../../utils/apiError';

/**
 * One team's roster on the "My Teams" surface (issue #470). Every member of the
 * team can open it; the response's `viewerCanManage` flag decides the mode: a LEAD
 * (or an admin) gets member management — add, promote/demote, remove — while a
 * plain member gets a read-only roster. Members render as {@link PersonLink}
 * everywhere (avatar, profile hover-card, click-through to the profile), like the
 * rest of the app. A lead is never offered "Remove from team" or a role change
 * on their own row (issue #542 follow-up: demoting a lead is another lead's or
 * an admin's call — admins keep the affordance); the server rejects self-removal
 * and self-role-change too. The last-lead guardrail surfaces as a toast.
 */
export function MyTeamDetailPage() {
  const { id = '' } = useParams();
  const { data: team, isLoading, isError } = useMyTeam(id);
  const setRole = useSetMyTeamMemberRole();
  const removeMember = useRemoveMyTeamMember();
  const currentUserId = useAuthStore((s) => s.userId);
  const viewerIsAdmin = useAuthStore(selectIsAdmin);
  const { formatDateTime } = useFormatters();
  const { toast, notify, clear } = useToast();

  const [addOpen, setAddOpen] = useState(false);
  const [addSeq, setAddSeq] = useState(0);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<TeamMember | null>(null);
  const [removeTarget, setRemoveTarget] = useState<TeamMember | null>(null);

  if (isError) {
    return (
      <Alert severity="error">
        This team could not be loaded, or you are not a member of it.{' '}
        <Link component={RouterLink} to="/my-teams" underline="hover">
          Back to my teams
        </Link>
      </Alert>
    );
  }
  if (isLoading || !team) {
    return <Typography color="text.secondary">Loading…</Typography>;
  }

  const canManage = team.viewerCanManage;
  const members = team.members ?? [];

  const openMenu = (event: MouseEvent<HTMLElement>, member: TeamMember) => {
    setAnchorEl(event.currentTarget);
    setActive(member);
  };
  const openAdd = () => {
    setAddOpen(true);
    setAddSeq((n) => n + 1);
  };

  // Mutations key off the canonical team id (the URL param may be a slug).
  const teamId = team.id;

  const toggleRole = (member: TeamMember) => {
    const next: TeamRole = member.teamRole === 'LEAD' ? 'MEMBER' : 'LEAD';
    setRole.mutate(
      { teamId, userId: member.userId, teamRole: next },
      { onError: (error) => notify(apiErrorMessage(error, 'Could not change the role.'), 'error') },
    );
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title={team.name}
        titleAdornment={team.viewerRole ? <TeamRoleBadge role={team.viewerRole} /> : undefined}
        description={team.description || undefined}
        action={
          canManage ? (
            <Button variant="contained" startIcon={<UserPlus size={18} />} onClick={openAdd}>
              Add member
            </Button>
          ) : undefined
        }
      />

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
          <TableHead>
            <TableRow>
              <TableCell>Member</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Joined</TableCell>
              {canManage && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {members.length === 0 && (
              <TableRow>
                <TableCell colSpan={canManage ? 4 : 3}>
                  <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                    {canManage ? 'No members yet. Add the first one.' : 'This team has no members.'}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {members.map((member) => (
              <TableRow key={member.userId} hover>
                <TableCell>
                  <PersonLink
                    userId={member.userId}
                    slug={member.slug}
                    name={member.displayName}
                    avatarUrl={member.avatarUrl}
                    size={34}
                  />
                </TableCell>
                <TableCell>
                  <TeamRoleBadge role={member.teamRole} />
                </TableCell>
                <TableCell>
                  <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                    {formatDateTime(member.joinedAt)}
                  </Typography>
                </TableCell>
                {canManage && (
                  <TableCell align="right">
                    {/* A non-admin lead has no actions on their own row: no
                        self-removal, no self-role-change (#542 follow-up). */}
                    {(member.userId !== currentUserId || viewerIsAdmin) && (
                      <IconButton
                        size="small"
                        aria-label={`Actions for ${member.displayName}`}
                        onClick={(e) => openMenu(e, member)}
                      >
                        <MoreVertical size={18} />
                      </IconButton>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      {canManage && (
        <>
          <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={() => setAnchorEl(null)}>
            {(active?.userId !== currentUserId || viewerIsAdmin) && (
              <MenuItem
                onClick={() => {
                  setAnchorEl(null);
                  if (active) toggleRole(active);
                }}
              >
                <ListItemIcon>
                  <ArrowLeftRight size={16} />
                </ListItemIcon>
                <ListItemText>
                  {active?.teamRole === 'LEAD' ? 'Make member' : 'Make lead'}
                </ListItemText>
              </MenuItem>
            )}
            {active?.userId !== currentUserId && (
              <MenuItem
                onClick={() => {
                  setAnchorEl(null);
                  setRemoveTarget(active);
                }}
              >
                <ListItemIcon>
                  <UserMinus size={16} />
                </ListItemIcon>
                <ListItemText>Remove from team</ListItemText>
              </MenuItem>
            )}
          </Menu>

          <AddMyTeamMemberDialog
            key={`add-${addSeq}`}
            open={addOpen}
            teamId={teamId}
            existingMemberIds={members.map((m) => m.userId)}
            onClose={() => setAddOpen(false)}
          />

          <ConfirmDialog
            open={removeTarget !== null}
            title="Remove member"
            message={`Remove ${removeTarget?.displayName} from this team?`}
            confirmLabel="Remove"
            destructive
            onConfirm={() => {
              if (removeTarget) {
                removeMember.mutate(
                  { teamId, userId: removeTarget.userId },
                  {
                    onError: (error) =>
                      notify(apiErrorMessage(error, 'Could not remove the member.'), 'error'),
                  },
                );
              }
              setRemoveTarget(null);
            }}
            onClose={() => setRemoveTarget(null)}
          />

          <AdminToast toast={toast} onClose={clear} />
        </>
      )}
    </Stack>
  );
}
