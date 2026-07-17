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

import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme, type SxProps, type Theme } from '@mui/material/styles';
import { ShieldCheck } from 'lucide-react';
import { NavLink } from 'react-router-dom';
import { useConfig } from '../../api/hooks/useConfig';
import { useAuthStore } from '../../stores/authStore';
import { BrandLogo } from '../branding/BrandLogo';
import { visibleNavGroups } from './navConfig';
import { UserFooter } from './UserFooter';

interface SidebarContentProps {
  collapsed: boolean;
  /** Called after a nav item is chosen (used to close the mobile drawer). */
  onNavigate?: () => void;
}

/**
 * The sidebar body shared by the desktop rail and the mobile drawer: brand,
 * the role-filtered navigation, and the user footer. When `collapsed`, labels
 * and group headings are hidden and icons get tooltips.
 */
export function SidebarContent({ collapsed, onNavigate }: SidebarContentProps) {
  const role = useAuthStore((s) => s.role);
  const teamLead = useAuthStore((s) => s.teamLead);
  const groups = visibleNavGroups(role, teamLead);
  const theme = useTheme();
  const branding = useConfig().data?.branding;
  const logomarkUrl = branding?.logomark.url;
  // Pick the logo variant for the sidebar surface: dark theme → light logo, and vice versa.
  const fullLogoUrl =
    theme.palette.mode === 'dark' ? branding?.logoDark.url : branding?.logoLight.url;

  // The brand-mark fallback box, shared by the collapsed and expanded fallbacks.
  const markBoxSx: SxProps<Theme> = {
    width: 30,
    height: 30,
    borderRadius: 1.75,
    bgcolor: 'primary.main',
    color: 'primary.contrastText',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  };

  return (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {/* Brand — driven by config.branding (issue #154), falling back to the wordmark */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1.25,
          px: collapsed ? 0 : 2.25,
          py: 2,
          justifyContent: collapsed ? 'center' : 'flex-start',
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        {collapsed ? (
          <BrandLogo
            url={logomarkUrl}
            alt="qnop"
            fallback={
              <Box sx={markBoxSx}>
                <ShieldCheck size={18} />
              </Box>
            }
            sx={{ width: 30, height: 30, borderRadius: 1.75, flexShrink: 0 }}
          />
        ) : (
          <BrandLogo
            url={fullLogoUrl}
            alt="qnop"
            sx={{ height: 30, maxWidth: 180 }}
            fallback={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
                <Box sx={markBoxSx}>
                  <ShieldCheck size={18} />
                </Box>
                <Box sx={{ minWidth: 0 }}>
                  <Typography
                    sx={{ fontWeight: 700, fontSize: 15, letterSpacing: '-0.015em', lineHeight: 1 }}
                  >
                    qnop
                  </Typography>
                  <Typography
                    sx={{
                      fontSize: 10.5,
                      letterSpacing: '0.08em',
                      textTransform: 'uppercase',
                      color: 'text.disabled',
                      mt: 0.4,
                      fontWeight: 500,
                    }}
                  >
                    Quality Notes · Sovereign
                  </Typography>
                </Box>
              </Box>
            }
          />
        )}
      </Box>

      {/* Navigation */}
      <Box sx={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', py: 1 }}>
        {groups.map((group, gi) => (
          <Box key={group.label || `g${gi}`} sx={{ px: 1, pt: gi === 0 ? 0.5 : 1.5 }}>
            {group.label && !collapsed && (
              <Typography
                sx={{
                  fontSize: 11,
                  textTransform: 'uppercase',
                  letterSpacing: '0.08em',
                  color: 'text.disabled',
                  fontWeight: 500,
                  px: 1.25,
                  pb: 1,
                }}
              >
                {group.label}
              </Typography>
            )}
            {group.label && collapsed && gi > 0 && <Divider sx={{ mx: 1, my: 1 }} />}
            <List disablePadding sx={{ display: 'flex', flexDirection: 'column', gap: 0.25 }}>
              {group.items.map((item) => {
                const button = (
                  <ListItemButton
                    key={item.id}
                    component={NavLink}
                    to={item.path}
                    end={item.path === '/'}
                    onClick={onNavigate}
                    sx={{
                      borderRadius: 1.75,
                      minHeight: 40,
                      px: collapsed ? 0 : 1.25,
                      justifyContent: collapsed ? 'center' : 'flex-start',
                      color: 'text.secondary',
                      '& .lucide': { strokeWidth: 1.75 },
                      '&.active': {
                        bgcolor: 'primary.light',
                        color: 'primary.main',
                        fontWeight: 600,
                        '& .MuiListItemText-primary': { fontWeight: 600 },
                      },
                      '&.active:hover': { bgcolor: 'primary.light' },
                    }}
                  >
                    <ListItemIcon
                      sx={{
                        minWidth: 0,
                        mr: collapsed ? 0 : 1.25,
                        color: 'inherit',
                        justifyContent: 'center',
                      }}
                    >
                      <item.icon size={18} />
                    </ListItemIcon>
                    {!collapsed && (
                      <ListItemText
                        primary={item.label}
                        slotProps={{ primary: { sx: { fontSize: 13.5, fontWeight: 500 } } }}
                      />
                    )}
                  </ListItemButton>
                );
                return collapsed ? (
                  <Tooltip key={item.id} title={item.label} placement="right">
                    {button}
                  </Tooltip>
                ) : (
                  button
                );
              })}
            </List>
          </Box>
        ))}
      </Box>

      <UserFooter collapsed={collapsed} />
    </Box>
  );
}
