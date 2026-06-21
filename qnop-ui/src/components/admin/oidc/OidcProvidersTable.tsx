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
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { MoreVertical, SquarePen, Trash2 } from 'lucide-react';
import type { OidcProviderDto } from '../../../api/generated';
import { formatDateTime } from '../../../utils/formatDate';
import { ToneBadge } from '../ToneBadge';
import { providerTypeLabel } from './oidcProviderTypes';

interface OidcProvidersTableProps {
  providers: OidcProviderDto[];
  onEdit: (provider: OidcProviderDto) => void;
  onToggleEnabled: (provider: OidcProviderDto) => void;
  onDelete: (provider: OidcProviderDto) => void;
}

const COLUMNS = ['Provider', 'Type', 'Client ID', 'Status', 'Created', ''];

export function OidcProvidersTable({
  providers,
  onEdit,
  onToggleEnabled,
  onDelete,
}: OidcProvidersTableProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<OidcProviderDto | null>(null);

  const openMenu = (event: MouseEvent<HTMLElement>, provider: OidcProviderDto) => {
    setAnchorEl(event.currentTarget);
    setActive(provider);
  };
  const closeMenu = () => setAnchorEl(null);

  return (
    <>
      <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
        <TableHead>
          <TableRow>
            {COLUMNS.map((col, index) => (
              <TableCell
                key={col || 'actions'}
                align={index === COLUMNS.length - 1 ? 'right' : 'left'}
              >
                {col}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {providers.length === 0 && (
            <TableRow>
              <TableCell colSpan={COLUMNS.length}>
                <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                  No identity providers configured yet.
                </Typography>
              </TableCell>
            </TableRow>
          )}
          {providers.map((provider) => (
            <TableRow key={provider.id} hover>
              <TableCell>
                <Typography sx={{ fontWeight: 600 }}>{provider.name}</Typography>
              </TableCell>
              <TableCell>
                <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {providerTypeLabel(provider.providerType)}
                </Typography>
              </TableCell>
              <TableCell>
                <Typography
                  sx={{ fontSize: 13, color: 'text.secondary', fontFamily: 'monospace' }}
                  noWrap
                >
                  {provider.clientId}
                </Typography>
              </TableCell>
              <TableCell>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <Tooltip title={provider.enabled ? 'Disable for login' : 'Enable for login'}>
                    <Switch
                      size="small"
                      checked={provider.enabled}
                      onChange={() => onToggleEnabled(provider)}
                      slotProps={{ input: { 'aria-label': `Toggle ${provider.name}` } }}
                    />
                  </Tooltip>
                  <ToneBadge
                    tone={provider.enabled ? 'green' : 'neutral'}
                    label={provider.enabled ? 'Enabled' : 'Disabled'}
                  />
                </Stack>
              </TableCell>
              <TableCell>
                <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {formatDateTime(provider.createdAt)}
                </Typography>
              </TableCell>
              <TableCell align="right">
                <IconButton
                  size="small"
                  aria-label={`Actions for ${provider.name}`}
                  onClick={(e) => openMenu(e, provider)}
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
          onClick={() => {
            closeMenu();
            if (active) onDelete(active);
          }}
        >
          <ListItemIcon>
            <Trash2 size={16} />
          </ListItemIcon>
          <ListItemText>Delete</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}
