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
import Switch from '@mui/material/Switch';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TableSortLabel from '@mui/material/TableSortLabel';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import type { ParseKeys } from 'i18next';
import { KeyRound, KeySquare, MoreVertical, SquarePen, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { AdminUserSummary } from '../../../api/generated';
import { UserHoverCard } from '../../people/UserHoverCard';
import { UserAvatar } from '../../shell/UserAvatar';
import { useFormatters } from '../../../hooks/useFormatters';
import { ToneBadge } from '../ToneBadge';
import { UserRoleBadge, UserSourceBadge, UserStatusBadge } from './UserBadges';

interface UsersTableProps {
  users: AdminUserSummary[];
  currentUserId: string | null;
  /** Active sort as `field,direction`. */
  sort: string;
  onSort: (field: string) => void;
  onEdit: (user: AdminUserSummary) => void;
  onResetPassword: (user: AdminUserSummary) => void;
  onGeneratePassword: (user: AdminUserSummary) => void;
  onToggleEnabled: (user: AdminUserSummary) => void;
  onDelete: (user: AdminUserSummary) => void;
}

/** Table columns; `labelKey` is an `admin` catalog key, absent for the unlabelled actions column. */
const COLUMNS: { key: string; labelKey?: ParseKeys<'admin'>; sortable?: boolean }[] = [
  { key: 'displayName', labelKey: 'users.table.columns.user', sortable: true },
  { key: 'role', labelKey: 'users.table.columns.role', sortable: true },
  { key: 'source', labelKey: 'users.table.columns.source' },
  { key: 'status', labelKey: 'users.table.columns.status' },
  { key: 'createdAt', labelKey: 'users.table.columns.created', sortable: true },
  { key: 'lastLoginAt', labelKey: 'users.table.columns.lastLogin', sortable: true },
  { key: 'actions' },
];

export function UsersTable({
  users,
  currentUserId,
  sort,
  onSort,
  onEdit,
  onResetPassword,
  onGeneratePassword,
  onToggleEnabled,
  onDelete,
}: UsersTableProps) {
  const { t } = useTranslation(['admin', 'common']);
  const { formatDateTime, formatRelative } = useFormatters();
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<AdminUserSummary | null>(null);

  const [sortField, sortDir] = sort.split(',');
  const direction = sortDir === 'desc' ? 'desc' : 'asc';

  const openMenu = (event: MouseEvent<HTMLElement>, user: AdminUserSummary) => {
    setAnchorEl(event.currentTarget);
    setActive(user);
  };
  const closeMenu = () => setAnchorEl(null);
  const isSelf = (user: AdminUserSummary) => user.id === currentUserId;

  return (
    <>
      <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
        <TableHead>
          <TableRow>
            {COLUMNS.map((col) => (
              <TableCell key={col.key} align={col.key === 'actions' ? 'right' : 'left'}>
                {col.sortable ? (
                  <TableSortLabel
                    active={sortField === col.key}
                    direction={sortField === col.key ? direction : 'asc'}
                    onClick={() => onSort(col.key)}
                  >
                    {col.labelKey && t(col.labelKey)}
                  </TableSortLabel>
                ) : (
                  col.labelKey && t(col.labelKey)
                )}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {users.length === 0 && (
            <TableRow>
              <TableCell colSpan={COLUMNS.length}>
                <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                  {t('users.table.empty')}
                </Typography>
              </TableCell>
            </TableRow>
          )}
          {users.map((user) => (
            <TableRow key={user.id} hover>
              <TableCell>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  <UserHoverCard userId={user.id} slug={user.slug} profileName={user.displayName}>
                    <UserAvatar name={user.displayName} size={36} imageUrl={user.avatarUrl} />
                  </UserHoverCard>
                  <Box sx={{ minWidth: 0 }}>
                    <Stack
                      direction="row"
                      spacing={0.75}
                      sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                    >
                      <UserHoverCard userId={user.id} slug={user.slug}>
                        <Typography sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
                          {user.displayName}
                        </Typography>
                      </UserHoverCard>
                      {user.passwordChangeRequired && (
                        <ToneBadge tone="amber" label={t('users.table.passwordChange')} />
                      )}
                    </Stack>
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
                <UserSourceBadge source={user.source} providerName={user.providerName} />
              </TableCell>
              <TableCell>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Tooltip
                    title={
                      isSelf(user)
                        ? t('users.table.cannotDisableSelf')
                        : t('users.table.toggleAccess')
                    }
                  >
                    <span>
                      <Switch
                        size="small"
                        checked={user.enabled}
                        disabled={isSelf(user)}
                        onChange={() => onToggleEnabled(user)}
                        slotProps={{
                          input: {
                            'aria-label': t('users.table.toggleUser', { name: user.displayName }),
                          },
                        }}
                      />
                    </span>
                  </Tooltip>
                  <UserStatusBadge enabled={user.enabled} />
                </Stack>
              </TableCell>
              <TableCell>
                <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {formatDateTime(user.createdAt)}
                </Typography>
              </TableCell>
              <TableCell>
                <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {formatRelative(user.lastLoginAt)}
                </Typography>
              </TableCell>
              <TableCell align="right">
                <IconButton
                  size="small"
                  aria-label={t('users.table.actionsFor', { name: user.displayName })}
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
          <ListItemText>{t('common:actions.edit')}</ListItemText>
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
          <ListItemText>{t('users.table.emailResetLink')}</ListItemText>
        </MenuItem>
        <MenuItem
          disabled={active?.source === 'EXTERNAL'}
          onClick={() => {
            closeMenu();
            if (active) onGeneratePassword(active);
          }}
        >
          <ListItemIcon>
            <KeySquare size={16} />
          </ListItemIcon>
          <ListItemText>{t('users.table.generatePassword')}</ListItemText>
        </MenuItem>
        <MenuItem
          disabled={active ? isSelf(active) : false}
          onClick={() => {
            closeMenu();
            if (active) onDelete(active);
          }}
        >
          <ListItemIcon>
            <Trash2 size={16} />
          </ListItemIcon>
          <ListItemText>{t('common:actions.delete')}</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}
