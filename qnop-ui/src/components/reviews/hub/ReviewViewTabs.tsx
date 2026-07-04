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

import { Link as RouterLink } from 'react-router-dom';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import { FileText, Focus, GitCompareArrows, SquareKanban } from 'lucide-react';
import { tokens } from '../../../theme/tokens';

/** The review's four ways of working (issue #398). */
export type ReviewView = 'document' | 'focus' | 'tasks' | 'compare';

interface ReviewViewTabsProps {
  documentId: string;
  active: ReviewView;
  /** Open + in-discussion count for the Tasks pill; omit to hide the pill. */
  openTaskCount?: number;
  /** Compare needs two extracted versions; disabled (with a tooltip) until then. */
  compareEnabled: boolean;
}

const TABS: { view: ReviewView; label: string; icon: LucideIcon; href: (id: string) => string }[] =
  [
    {
      view: 'document',
      label: 'Document',
      icon: FileText,
      href: (id) => `/reviews/${id}?view=panel`,
    },
    { view: 'focus', label: 'Focus', icon: Focus, href: (id) => `/reviews/${id}?view=focus` },
    { view: 'tasks', label: 'Tasks', icon: SquareKanban, href: (id) => `/reviews/${id}/tasks` },
    {
      view: 'compare',
      label: 'Compare',
      icon: GitCompareArrows,
      href: (id) => `/reviews/${id}/compare`,
    },
  ];

/**
 * The review's view switcher (issue #398, prototype `rh-tab`): one labeled,
 * always-visible strip under the hub head on every review page — Document,
 * Focus, Tasks (with the open-count pill), Compare. Every tab is a plain
 * link (router tabs, not ARIA tabpanels), so the strip is stateless and
 * identical everywhere; the active tab carries the prototype's 2px accent
 * underline. Icon-only navigation was the discoverability problem this
 * replaces — labels stay at every width.
 */
export function ReviewViewTabs({
  documentId,
  active,
  openTaskCount,
  compareEnabled,
}: ReviewViewTabsProps) {
  const theme = useTheme();
  return (
    <Box
      component="nav"
      aria-label="Review views"
      sx={{
        borderBottom: '1px solid',
        borderColor: 'divider',
        overflowX: 'auto',
        flexShrink: 0,
      }}
    >
      <Stack direction="row" spacing={2.75} sx={{ px: 0.5 }}>
        {TABS.map(({ view, label, icon: Icon, href }) => {
          const isActive = view === active;
          const isCompareDisabled = view === 'compare' && !compareEnabled;
          const tab = (
            <ButtonBase
              key={view}
              component={isCompareDisabled ? 'span' : RouterLink}
              to={isCompareDisabled ? undefined : href(documentId)}
              disabled={isCompareDisabled}
              aria-current={isActive ? 'page' : undefined}
              data-testid={`review-view-tab-${view}`}
              sx={{
                position: 'relative',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                py: 1.1,
                px: 0.5,
                whiteSpace: 'nowrap',
                fontFamily: 'inherit',
                fontSize: 14,
                fontWeight: 500,
                color: isCompareDisabled
                  ? 'text.disabled'
                  : isActive
                    ? 'text.primary'
                    : 'text.secondary',
                transition: 'color 120ms ease',
                '&:hover': { color: isCompareDisabled ? 'text.disabled' : 'text.primary' },
                '&:focus-visible': { boxShadow: theme.qnop.focusRing, borderRadius: 0.5 },
                '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
                // The prototype's rh-tab.on::after — a 2px accent underline
                // overlapping the strip's divider.
                '&::after': {
                  content: '""',
                  position: 'absolute',
                  left: 0,
                  right: 0,
                  bottom: -1,
                  height: 2,
                  borderRadius: 2,
                  bgcolor: isActive ? theme.qnop.brand.blue : 'transparent',
                },
              }}
            >
              <Icon size={15} aria-hidden />
              {label}
              {view === 'tasks' && openTaskCount !== undefined && (
                <Typography
                  component="span"
                  sx={{
                    fontSize: 11,
                    fontWeight: 600,
                    lineHeight: 1,
                    px: 0.75,
                    py: 0.4,
                    borderRadius: 999,
                    fontVariantNumeric: 'tabular-nums',
                    bgcolor:
                      openTaskCount > 0 ? alpha(theme.qnop.brand.blue, 0.12) : theme.qnop.surface2,
                    color:
                      openTaskCount > 0
                        ? theme.palette.mode === 'dark'
                          ? tokens.badge.blue.fgDark
                          : tokens.badge.blue.fg
                        : 'text.secondary',
                  }}
                >
                  {openTaskCount}
                </Typography>
              )}
            </ButtonBase>
          );
          return isCompareDisabled ? (
            <Tooltip key={view} title="Needs two extracted versions">
              <span>{tab}</span>
            </Tooltip>
          ) : (
            tab
          );
        })}
      </Stack>
    </Box>
  );
}
