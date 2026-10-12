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
import ButtonBase from '@mui/material/ButtonBase';
import ListItemIcon from '@mui/material/ListItemIcon';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Typography from '@mui/material/Typography';
import { KeyRound, LogOut, UserRound } from 'lucide-react';
import { useNavigate } from 'react-router';
import { useAuthStore } from '../../stores/authStore';
import { UserAvatar } from './UserAvatar';
import { useTranslation } from 'react-i18next';

interface UserFooterProps {
  collapsed: boolean;
}

/** Sidebar footer: the signed-in user with a menu (profile, change password, logout). */
export function UserFooter({ collapsed }: UserFooterProps) {
  const { t } = useTranslation('shell');
  const displayName = useAuthStore((s) => s.displayName);
  const role = useAuthStore((s) => s.role);
  const source = useAuthStore((s) => s.source);
  const avatarUrl = useAuthStore((s) => s.avatarUrl);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  const onLogout = async () => {
    setAnchor(null);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <Box sx={{ borderTop: 1, borderColor: 'divider', p: collapsed ? 1 : 1.5 }}>
      <ButtonBase
        onClick={(e: MouseEvent<HTMLElement>) => setAnchor(e.currentTarget)}
        aria-label={t('userMenu.trigger')}
        sx={{
          width: '100%',
          gap: 1.25,
          borderRadius: 1.75,
          p: 0.75,
          justifyContent: collapsed ? 'center' : 'flex-start',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        <UserAvatar name={displayName} size={28} imageUrl={avatarUrl} />
        {!collapsed && (
          <Box sx={{ minWidth: 0, textAlign: 'left', flex: 1 }}>
            <Typography noWrap sx={{ fontSize: 13, fontWeight: 500, lineHeight: 1.2 }}>
              {displayName ?? t('userMenu.unknownUser')}
            </Typography>
            <Typography noWrap sx={{ fontSize: 11, color: 'text.disabled' }}>
              {role ? t(`userMenu.roles.${role}`) : ''}
            </Typography>
          </Box>
        )}
      </ButtonBase>

      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
        transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      >
        <MenuItem
          onClick={() => {
            setAnchor(null);
            navigate('/profile');
          }}
        >
          <ListItemIcon>
            <UserRound size={16} />
          </ListItemIcon>
          {t('userMenu.profile')}
        </MenuItem>
        {/* External (OIDC) accounts have no local password — only local users can change it. */}
        {source !== 'EXTERNAL' && (
          <MenuItem
            onClick={() => {
              setAnchor(null);
              navigate('/change-password');
            }}
          >
            <ListItemIcon>
              <KeyRound size={16} />
            </ListItemIcon>
            {t('userMenu.changePassword')}
          </MenuItem>
        )}
        <MenuItem onClick={onLogout}>
          <ListItemIcon>
            <LogOut size={16} />
          </ListItemIcon>
          {t('userMenu.signOut')}
        </MenuItem>
      </Menu>
    </Box>
  );
}
