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
import Box from '@mui/material/Box';
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
import { ArrowLeftRight, MoreVertical, SquarePen, UserMinus, UserPlus } from 'lucide-react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { AdminTeamMember } from '../../api/generated';
import { useRemoveTeamMember, useSetTeamMemberRole, useTeam } from '../../api/hooks/useTeams';
import { AddMemberDialog } from '../../components/admin/teams/AddMemberDialog';
import { TeamFormDialog } from '../../components/admin/teams/TeamFormDialog';
import { TeamRoleBadge } from '../../components/admin/teams/TeamRoleBadge';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { UserAvatar } from '../../components/shell/UserAvatar';
import { UserStatusBadge } from '../../components/admin/users/UserBadges';
import { useFormatters } from '../../hooks/useFormatters';

/** Team detail with member management: add, change role, remove (#105). */
export function TeamDetailPage() {
  const { id = '' } = useParams();
  const { data: team, isLoading, isError } = useTeam(id);
  const setRole = useSetTeamMemberRole();
  const removeMember = useRemoveTeamMember();
  const { formatDateTime } = useFormatters();

  const [editOpen, setEditOpen] = useState(false);
  const [editSeq, setEditSeq] = useState(0);
  const [addOpen, setAddOpen] = useState(false);
  const [addSeq, setAddSeq] = useState(0);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<AdminTeamMember | null>(null);
  const [removeTarget, setRemoveTarget] = useState<AdminTeamMember | null>(null);

  if (isError) {
    return (
      <Alert severity="error">
        This team could not be loaded.{' '}
        <Link component={RouterLink} to="/admin/teams" underline="hover">
          Back to teams
        </Link>
      </Alert>
    );
  }
  if (isLoading || !team) {
    return <Typography color="text.secondary">Loading…</Typography>;
  }

  const openMenu = (event: MouseEvent<HTMLElement>, member: AdminTeamMember) => {
    setAnchorEl(event.currentTarget);
    setActive(member);
  };
  const openAdd = () => {
    setAddOpen(true);
    setAddSeq((n) => n + 1);
  };
  const openEdit = () => {
    setEditOpen(true);
    setEditSeq((n) => n + 1);
  };

  const toggleRole = (member: AdminTeamMember) => {
    const next = member.teamRole === 'LEAD' ? 'MEMBER' : 'LEAD';
    setRole.mutate({ teamId: id, userId: member.userId, teamRole: next });
  };

  const members = team.members ?? [];

  return (
    <Stack spacing={3}>
      <PageHeader
        title={team.name}
        titleAdornment={<UserStatusBadge enabled={team.enabled} />}
        description={team.description || undefined}
        action={
          <Stack direction="row" spacing={1.5}>
            <Button color="inherit" startIcon={<SquarePen size={16} />} onClick={openEdit}>
              Edit
            </Button>
            <Button variant="contained" startIcon={<UserPlus size={18} />} onClick={openAdd}>
              Add member
            </Button>
          </Stack>
        }
      />

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
          <TableHead>
            <TableRow>
              <TableCell>Member</TableCell>
              <TableCell>Role</TableCell>
              <TableCell>Joined</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {members.length === 0 && (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                    No members yet. Add the first one.
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {members.map((member) => (
              <TableRow key={member.userId} hover>
                <TableCell>
                  <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                    <UserAvatar name={member.displayName} size={36} />
                    <Box sx={{ minWidth: 0 }}>
                      <Typography sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
                        {member.displayName}
                      </Typography>
                      <Typography sx={{ fontSize: 13, color: 'text.secondary' }} noWrap>
                        {member.email}
                      </Typography>
                    </Box>
                  </Stack>
                </TableCell>
                <TableCell>
                  <TeamRoleBadge role={member.teamRole} />
                </TableCell>
                <TableCell>
                  <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                    {formatDateTime(member.joinedAt)}
                  </Typography>
                </TableCell>
                <TableCell align="right">
                  <IconButton
                    size="small"
                    aria-label={`Actions for ${member.displayName}`}
                    onClick={(e) => openMenu(e, member)}
                  >
                    <MoreVertical size={18} />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={() => setAnchorEl(null)}>
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            if (active) toggleRole(active);
          }}
        >
          <ListItemIcon>
            <ArrowLeftRight size={16} />
          </ListItemIcon>
          <ListItemText>{active?.teamRole === 'LEAD' ? 'Make member' : 'Make lead'}</ListItemText>
        </MenuItem>
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
      </Menu>

      <AddMemberDialog
        key={`add-${addSeq}`}
        open={addOpen}
        teamId={id}
        existingMemberIds={members.map((m) => m.userId)}
        onClose={() => setAddOpen(false)}
      />

      <TeamFormDialog
        key={`edit-${editSeq}`}
        open={editOpen}
        mode="edit"
        team={{
          id: team.id,
          name: team.name,
          description: team.description,
          enabled: team.enabled,
          memberCount: members.length,
          createdAt: team.createdAt,
          updatedAt: team.updatedAt,
        }}
        onClose={() => setEditOpen(false)}
      />

      <ConfirmDialog
        open={removeTarget !== null}
        title="Remove member"
        message={`Remove ${removeTarget?.displayName} from this team?`}
        confirmLabel="Remove"
        destructive
        onConfirm={() => {
          if (removeTarget) {
            removeMember.mutate({ teamId: id, userId: removeTarget.userId });
          }
          setRemoveTarget(null);
        }}
        onClose={() => setRemoveTarget(null)}
      />
    </Stack>
  );
}
