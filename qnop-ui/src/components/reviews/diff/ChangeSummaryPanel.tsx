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
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { ArrowRight } from 'lucide-react';
import type { DiffChange } from '../../../api/generated';
import { DiffChangeType } from '../../../api/generated';
import { tokens } from '../../../theme/tokens';
import { CHANGE_KIND, changePageNumber, diffStats, excerpt } from './diffModel';

interface ChangeSummaryPanelProps {
  changes: DiffChange[];
  activeChangeIndex: number | null;
  /** Toggles: selecting the active change again clears the selection. */
  onSelectChange: (changeIndex: number | null) => void;
}

/**
 * The comparison's right-hand summary (design prototype `diff.jsx`): one card
 * per change — type label in the diff colour language, the affected text, the
 * page — plus the statistics block. Clicking a card selects the change and
 * scrolls both panes to its location; the active card carries the deeper
 * left rail, mirroring the highlight's deeper wash.
 */
export function ChangeSummaryPanel({
  changes,
  activeChangeIndex,
  onSelectChange,
}: ChangeSummaryPanelProps) {
  const theme = useTheme();
  const dark = theme.palette.mode === 'dark';
  const stats = diffStats(changes);

  const railColor = (type: DiffChangeType) =>
    type === DiffChangeType.Inserted
      ? theme.palette.success.main
      : type === DiffChangeType.Deleted
        ? theme.palette.error.main
        : theme.palette.warning.main;

  return (
    <Stack spacing={1.5} data-testid="change-summary">
      <Typography
        variant="overline"
        sx={{ color: 'text.secondary', lineHeight: 1.5, letterSpacing: '0.08em' }}
      >
        Changes ({changes.length})
      </Typography>

      {changes.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          The compared versions have identical text content.
        </Typography>
      )}

      {changes.map((change, index) => {
        const kind = CHANGE_KIND[change.type];
        const badge = tokens.badge[kind.tone];
        const rail = railColor(change.type);
        const active = index === activeChangeIndex;
        const page = changePageNumber(change);
        const isChanged = change.type === DiffChangeType.Changed;
        return (
          <ButtonBase
            key={index}
            data-testid={`change-card-${index}`}
            onClick={() => onSelectChange(active ? null : index)}
            aria-pressed={active}
            sx={{
              display: 'block',
              textAlign: 'left',
              width: '100%',
              p: 1.5,
              borderRadius: 2,
              bgcolor: badge.bg,
              borderLeft: `3px solid ${rail}`,
              transition: 'box-shadow 120ms ease, background-color 120ms ease',
              ...(active && { boxShadow: `inset 0 0 0 1px ${alpha(rail, 0.55)}` }),
              '&:hover': { bgcolor: alpha(rail, dark ? 0.18 : 0.14) },
              '&:focus-visible': { boxShadow: theme.qnop.focusRing },
              '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
            }}
          >
            <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', mb: 0.5 }}>
              <Typography
                component="span"
                sx={{
                  fontSize: 10,
                  fontWeight: 700,
                  letterSpacing: '0.06em',
                  textTransform: 'uppercase',
                  color: dark ? badge.fgDark : badge.fg,
                }}
              >
                {kind.label}
              </Typography>
              {page !== null && (
                <Typography
                  component="span"
                  sx={{
                    fontSize: 11,
                    color: 'text.secondary',
                    fontFamily: tokens.font.mono,
                    marginLeft: 'auto',
                  }}
                >
                  p. {page}
                </Typography>
              )}
            </Stack>
            {isChanged ? (
              <Stack spacing={0.25}>
                <Typography
                  variant="body2"
                  sx={{ color: 'text.secondary', textDecoration: 'line-through', lineHeight: 1.45 }}
                >
                  {excerpt(change.fromText, 90)}
                </Typography>
                <Stack direction="row" spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                  <ArrowRight
                    size={13}
                    aria-hidden
                    style={{ color: theme.palette.text.secondary, flexShrink: 0, marginTop: 3 }}
                  />
                  <Typography variant="body2" sx={{ lineHeight: 1.45 }}>
                    {excerpt(change.toText, 90)}
                  </Typography>
                </Stack>
              </Stack>
            ) : (
              <Typography
                variant="body2"
                sx={{
                  lineHeight: 1.45,
                  ...(change.type === DiffChangeType.Deleted && {
                    textDecoration: 'line-through',
                    color: 'text.secondary',
                  }),
                }}
              >
                {excerpt(change.type === DiffChangeType.Deleted ? change.fromText : change.toText)}
              </Typography>
            )}
          </ButtonBase>
        );
      })}

      {changes.length > 0 && (
        <Box
          sx={{
            mt: 1,
            p: 1.5,
            borderRadius: 2,
            bgcolor: theme.qnop.surface2,
            border: `1px solid ${theme.palette.divider}`,
          }}
        >
          <Typography
            variant="overline"
            sx={{ color: 'text.secondary', lineHeight: 2, letterSpacing: '0.08em' }}
          >
            Statistics
          </Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.25 }}>
            {[
              {
                label: 'Words +',
                value: `+${stats.addedWords}`,
                color: dark ? tokens.badge.green.fgDark : tokens.semantic.successStrong,
              },
              {
                label: 'Words −',
                value: `−${stats.removedWords}`,
                color: dark ? tokens.badge.red.fgDark : tokens.semantic.dangerStrong,
              },
              {
                label: 'Changes',
                value: `${changes.length}`,
                color: theme.palette.text.primary,
              },
              {
                label: stats.pages.length === 1 ? 'Page' : 'Pages',
                value: stats.pages.join(', ') || '—',
                color: theme.palette.text.primary,
              },
            ].map((stat) => (
              <Box key={stat.label}>
                <Typography sx={{ fontSize: 10.5, color: 'text.secondary' }}>
                  {stat.label}
                </Typography>
                <Typography
                  sx={{
                    fontFamily: tokens.font.mono,
                    fontSize: 13,
                    fontWeight: 600,
                    color: stat.color,
                  }}
                >
                  {stat.value}
                </Typography>
              </Box>
            ))}
          </Box>
        </Box>
      )}
    </Stack>
  );
}
