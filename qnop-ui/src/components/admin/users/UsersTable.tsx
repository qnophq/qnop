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
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { KeyRound, MoreVertical, SquarePen } from 'lucide-react';
import type { AdminUserSummary } from '../../../api/generated';
import { UserAvatar } from '../../shell/UserAvatar';
import { formatDateTime } from '../../../utils/formatDate';
import { UserRoleBadge, UserStatusBadge } from './UserBadges';

interface UsersTableProps {
  users: AdminUserSummary[];
  onEdit: (user: AdminUserSummary) => void;
  onResetPassword: (user: AdminUserSummary) => void;
}

export function UsersTable({ users, onEdit, onResetPassword }: UsersTableProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<AdminUserSummary | null>(null);

  const openMenu = (event: MouseEvent<HTMLElement>, user: AdminUserSummary) => {
    setAnchorEl(event.currentTarget);
    setActive(user);
  };
  const closeMenu = () => setAnchorEl(null);

  return (
    <>
      <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
        <TableHead>
          <TableRow>
            <TableCell>User</TableCell>
            <TableCell>Role</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Last login</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {users.length === 0 && (
            <TableRow>
              <TableCell colSpan={5}>
                <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                  No users found.
                </Typography>
              </TableCell>
            </TableRow>
          )}
          {users.map((user) => (
            <TableRow key={user.id} hover>
              <TableCell>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  <UserAvatar name={user.displayName} size={36} />
                  <Box sx={{ minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
                      {user.displayName}
                      {user.source === 'EXTERNAL' && (
                        <Typography
                          component="span"
                          sx={{ ml: 1, fontSize: 11, color: 'text.secondary', fontWeight: 500 }}
                        >
                          OIDC
                        </Typography>
                      )}
                    </Typography>
                    <Typography sx={{ fontSize: 13, color: 'text.secondary' }} noWrap>
                      {user.email}
                    </Typography>
                  </Box>
                </Stack>
              </TableCell>
              <TableCell>
                <UserRoleBadge role={user.role} />
              </TableCell>
              <TableCell>
                <UserStatusBadge enabled={user.enabled} />
              </TableCell>
              <TableCell>
                <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {formatDateTime(user.lastLoginAt)}
                </Typography>
              </TableCell>
              <TableCell align="right">
                <IconButton
                  size="small"
                  aria-label={`Actions for ${user.displayName}`}
                  onClick={(e) => openMenu(e, user)}
                >
                  <MoreVertical size={18} />
                </IconButton>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={closeMenu}>
        <MenuItem
          onClick={() => {
            closeMenu();
            if (active) onEdit(active);
          }}
        >
          <ListItemIcon>
            <SquarePen size={16} />
          </ListItemIcon>
          <ListItemText>Edit</ListItemText>
        </MenuItem>
        <MenuItem
          disabled={active?.source === 'EXTERNAL'}
          onClick={() => {
            closeMenu();
            if (active) onResetPassword(active);
          }}
        >
          <ListItemIcon>
            <KeyRound size={16} />
          </ListItemIcon>
          <ListItemText>Send password link</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}
