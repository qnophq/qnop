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

import { useState } from 'react';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import MenuItem from '@mui/material/MenuItem';
import Popover from '@mui/material/Popover';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { ListFilter, Search } from 'lucide-react';
import { AnnotationPriority, AnnotationStatus, AnnotationType } from '../../../api/generated';
import { UserAvatar } from '../../shell/UserAvatar';
import { STATUS_CUES } from './statusCues';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';
import type { AnnotationFilters } from './panelFilters';
import { EMPTY_FILTERS, activeFacetCount } from './panelFilters';

/** Compact facet pills living inside the search field — no second row, no layout shift. */
const PILL_SX = {
  height: 20,
  fontSize: 11,
  fontWeight: 600,
  bgcolor: 'background.paper',
  border: '1px solid',
  borderColor: 'divider',
  '& .MuiChip-deleteIcon': { fontSize: 14 },
  '& .MuiChip-icon': { ml: 0.5, mr: -0.25 },
} as const;

/** The re-anchoring facet (ADR-0009, issue #326). */
const PLACEMENT_OPTIONS: { value: AnnotationFilters['placement']; label: string }[] = [
  { value: 'all', label: 'Any placement' },
  { value: 'attention', label: 'Needs attention' },
  { value: 'moved', label: 'Moved' },
  { value: 'orphaned', label: 'Orphaned' },
];

const STATUS_OPTIONS: { value: AnnotationFilters['status']; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'resolved', label: 'Resolved' },
];

export interface FilterAuthor {
  id: string;
  name: string;
}

interface PanelFilterBarProps {
  filters: AnnotationFilters;
  onChange: (filters: AnnotationFilters) => void;
  /** Distinct annotation authors, names resolved server-side (empty in anonymous reviews). */
  authors: FilterAuthor[];
  /** Hide the status facet — the tasks view speaks status through its column chips. */
  statusFacet?: boolean;
  /** Hide the author facet — an anonymous review has no meaningful author filter (issue #413). */
  authorFacet?: boolean;
  /** The search field's placeholder and accessible name. */
  searchLabel?: string;
}

/**
 * The panel's search-and-filter head (issue #403): a rounded full-text field
 * plus a filter button that tucks the facets — status, type, priority,
 * author — into a popover, tracker-style. Active facets live as compact
 * removable pills INSIDE the search field (the Linear/GitHub pattern), so the
 * filtered state stays visible and reversible without a second row — nothing
 * below ever jumps.
 */
export function PanelFilterBar({
  filters,
  onChange,
  authors,
  statusFacet = true,
  authorFacet = true,
  searchLabel = 'Search annotations',
}: PanelFilterBarProps) {
  const theme = useTheme();
  const [anchorEl, setAnchorEl] = useState<HTMLButtonElement | null>(null);
  const facets = activeFacetCount(filters);

  const set = (patch: Partial<AnnotationFilters>) => onChange({ ...filters, ...patch });
  const nameOf = (id: string) => authors.find((author) => author.id === id)?.name ?? 'Participant';

  return (
    <Box>
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <Stack
          direction="row"
          spacing={0.75}
          sx={{
            flex: 1,
            minWidth: 0,
            alignItems: 'center',
            px: 1.25,
            py: 0.5,
            borderRadius: '12px',
            bgcolor: theme.qnop.surface2,
            color: 'text.secondary',
            transition: 'box-shadow 120ms ease',
            '&:focus-within': {
              boxShadow: `0 0 0 2px ${alpha(theme.qnop.brand.blue, 0.25)}`,
            },
            '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
          }}
        >
          <Search size={14} aria-hidden style={{ flexShrink: 0 }} />
          {facets > 0 && (
            <Stack
              direction="row"
              spacing={0.5}
              data-testid="active-filter-chips"
              sx={{ alignItems: 'center', flexShrink: 0, maxWidth: '60%', overflow: 'hidden' }}
            >
              {filters.placement !== 'all' && (
                <Chip
                  size="small"
                  label={PLACEMENT_OPTIONS.find((o) => o.value === filters.placement)?.label}
                  onDelete={() => set({ placement: 'all' })}
                />
              )}
              {statusFacet && filters.status !== 'all' && (
                <Chip
                  size="small"
                  icon={(() => {
                    const cue =
                      STATUS_CUES[
                        filters.status === 'open'
                          ? AnnotationStatus.Open
                          : AnnotationStatus.Resolved
                      ];
                    const CueIcon = cue.icon;
                    return <CueIcon size={12} color={cue.color(theme)} aria-hidden />;
                  })()}
                  label={filters.status === 'open' ? 'Open' : 'Resolved'}
                  onDelete={() => set({ status: 'all' })}
                  sx={PILL_SX}
                />
              )}
              {filters.type !== null && (
                <Chip
                  size="small"
                  icon={(() => {
                    const cue = TYPE_CUES[filters.type];
                    const CueIcon = cue.icon;
                    return <CueIcon size={12} color={cue.color(theme)} aria-hidden />;
                  })()}
                  label={TYPE_CUES[filters.type].label}
                  onDelete={() => set({ type: null })}
                  sx={PILL_SX}
                />
              )}
              {filters.priority !== null && (
                <Chip
                  size="small"
                  icon={
                    <Box
                      sx={{
                        width: 7,
                        height: 7,
                        borderRadius: '50%',
                        bgcolor: PRIORITY_CUES[filters.priority].color(theme),
                      }}
                      aria-hidden
                    />
                  }
                  label={PRIORITY_CUES[filters.priority].label}
                  onDelete={() => set({ priority: null })}
                  sx={PILL_SX}
                />
              )}
              {authorFacet && filters.author !== null && (
                <Chip
                  size="small"
                  icon={<UserAvatar name={nameOf(filters.author)} size={14} imageUrl={null} />}
                  label={nameOf(filters.author)}
                  onDelete={() => set({ author: null })}
                  sx={PILL_SX}
                />
              )}
            </Stack>
          )}
          <InputBase
            fullWidth
            placeholder={searchLabel}
            value={filters.query}
            onChange={(event) => set({ query: event.target.value })}
            inputProps={{ 'aria-label': searchLabel }}
            sx={{ fontSize: 13, p: 0, color: 'text.primary' }}
          />
        </Stack>
        <Tooltip title="Filter annotations">
          <IconButton
            size="small"
            aria-label="Filter annotations"
            aria-expanded={Boolean(anchorEl)}
            onClick={(event) => setAnchorEl(event.currentTarget)}
          >
            <Badge badgeContent={facets} color="primary" invisible={facets === 0}>
              <ListFilter size={16} />
            </Badge>
          </IconButton>
        </Tooltip>
      </Stack>

      <Popover
        open={Boolean(anchorEl)}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { width: 260, p: 1.5 } } }}
      >
        <Stack spacing={1.5}>
          {statusFacet && (
            <TextField
              select
              size="small"
              label="Status"
              value={filters.status}
              onChange={(event) =>
                set({ status: event.target.value as AnnotationFilters['status'] })
              }
            >
              {STATUS_OPTIONS.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
          )}
          <TextField
            select
            size="small"
            label="Placement"
            value={filters.placement}
            onChange={(event) =>
              set({ placement: event.target.value as AnnotationFilters['placement'] })
            }
          >
            {PLACEMENT_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label="Type"
            value={filters.type ?? ''}
            onChange={(event) =>
              set({ type: (event.target.value || null) as AnnotationType | null })
            }
          >
            <MenuItem value="">Any</MenuItem>
            {Object.values(AnnotationType).map((type) => {
              const cue = TYPE_CUES[type];
              const CueIcon = cue.icon;
              return (
                <MenuItem key={type} value={type}>
                  <Stack
                    direction="row"
                    spacing={0.75}
                    sx={{ alignItems: 'center', color: cue.color(theme) }}
                  >
                    <CueIcon size={13} aria-hidden />
                    <Typography component="span" variant="body2">
                      {cue.label}
                    </Typography>
                  </Stack>
                </MenuItem>
              );
            })}
          </TextField>
          <TextField
            select
            size="small"
            label="Priority"
            value={filters.priority ?? ''}
            onChange={(event) =>
              set({ priority: (event.target.value || null) as AnnotationPriority | null })
            }
          >
            <MenuItem value="">Any</MenuItem>
            {Object.values(AnnotationPriority).map((priority) => {
              const cue = PRIORITY_CUES[priority];
              return (
                <MenuItem key={priority} value={priority}>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                    <Box
                      sx={{
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        bgcolor: cue.color(theme),
                        flexShrink: 0,
                      }}
                      aria-hidden
                    />
                    <Typography component="span" variant="body2">
                      {cue.label}
                    </Typography>
                  </Stack>
                </MenuItem>
              );
            })}
          </TextField>
          {authorFacet && (
            <TextField
              select
              size="small"
              label="Author"
              value={filters.author ?? ''}
              onChange={(event) => set({ author: event.target.value || null })}
            >
              <MenuItem value="">Anyone</MenuItem>
              {authors.map((author) => (
                <MenuItem key={author.id} value={author.id}>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
                    <UserAvatar name={author.name} size={18} imageUrl={null} />
                    <Typography component="span" variant="body2">
                      {author.name}
                    </Typography>
                  </Stack>
                </MenuItem>
              ))}
            </TextField>
          )}
          {facets > 0 && (
            <Button
              size="small"
              variant="text"
              onClick={() => onChange({ ...EMPTY_FILTERS, query: filters.query })}
              sx={{ alignSelf: 'flex-end' }}
            >
              Reset filters
            </Button>
          )}
        </Stack>
      </Popover>
    </Box>
  );
}
