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
import { alpha, useTheme } from '@mui/material/styles';
import { ListFilter, Search } from 'lucide-react';
import { AnnotationPriority, AnnotationType } from '../../../api/generated';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';
import type { AnnotationFilters } from './panelFilters';
import { EMPTY_FILTERS, activeFacetCount } from './panelFilters';

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
  /** Distinct annotation authors, names resolved where the directory knows them. */
  authors: FilterAuthor[];
}

/**
 * The panel's search-and-filter head (issue #403): a rounded full-text field
 * plus a filter button that tucks the facets — status, type, priority,
 * author — into a popover, tracker-style. Active facets surface as small
 * removable chips underneath, so the filtered state stays visible and
 * reversible without reopening the popover.
 */
export function PanelFilterBar({ filters, onChange, authors }: PanelFilterBarProps) {
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
          <InputBase
            fullWidth
            placeholder="Search annotations"
            value={filters.query}
            onChange={(event) => set({ query: event.target.value })}
            inputProps={{ 'aria-label': 'Search annotations' }}
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

      {facets > 0 && (
        <Stack
          direction="row"
          spacing={0.5}
          data-testid="active-filter-chips"
          sx={{ mt: 0.75, flexWrap: 'wrap', rowGap: 0.5 }}
        >
          {filters.status !== 'all' && (
            <Chip
              size="small"
              label={filters.status === 'open' ? 'Open' : 'Resolved'}
              onDelete={() => set({ status: 'all' })}
            />
          )}
          {filters.type !== null && (
            <Chip
              size="small"
              label={TYPE_CUES[filters.type].label}
              onDelete={() => set({ type: null })}
            />
          )}
          {filters.priority !== null && (
            <Chip
              size="small"
              label={`${PRIORITY_CUES[filters.priority].label} priority`}
              onDelete={() => set({ priority: null })}
            />
          )}
          {filters.author !== null && (
            <Chip
              size="small"
              label={nameOf(filters.author)}
              onDelete={() => set({ author: null })}
            />
          )}
        </Stack>
      )}

      <Popover
        open={Boolean(anchorEl)}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { width: 260, p: 1.5 } } }}
      >
        <Stack spacing={1.5}>
          <TextField
            select
            size="small"
            label="Status"
            value={filters.status}
            onChange={(event) => set({ status: event.target.value as AnnotationFilters['status'] })}
          >
            {STATUS_OPTIONS.map((option) => (
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
            {Object.values(AnnotationType).map((type) => (
              <MenuItem key={type} value={type}>
                {TYPE_CUES[type].label}
              </MenuItem>
            ))}
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
            {Object.values(AnnotationPriority).map((priority) => (
              <MenuItem key={priority} value={priority}>
                {PRIORITY_CUES[priority].label}
              </MenuItem>
            ))}
          </TextField>
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
                {author.name}
              </MenuItem>
            ))}
          </TextField>
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
