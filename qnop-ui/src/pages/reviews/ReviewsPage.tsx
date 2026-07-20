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

import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Typography from '@mui/material/Typography';
import { LayoutGrid, Plus, Rows3, Search, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { DocumentSummary } from '../../api/generated';
import { useReviews } from '../../api/hooks/useReviews';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { isOpenWorkflowState } from '../../components/reviews/workflowMeta';
import { roleOf } from '../../components/reviews/list/reviewListModel';
import { ReviewCards } from '../../components/reviews/list/ReviewCards';
import { ReviewsTable } from '../../components/reviews/list/ReviewsTable';
import { ReviewsEmptyState } from './ReviewsEmptyState';
import { useAuthStore } from '../../stores/authStore';

type RoleFilter = 'all' | 'owner' | 'reviewer';
type StatusFilter = 'all' | 'open' | 'closed';
type SortBy = 'updated' | 'name' | 'due';
type ViewMode = 'table' | 'cards';

const VIEW_STORAGE_KEY = 'qnop-reviews-view';
// The overview loads one big page and filters client-side so the chip counters
// stay consistent with what is on screen; server paging arrives when real
// installations outgrow this (documented trade-off in #251).
const FETCH_SIZE = 100;

function readStoredView(): ViewMode {
  try {
    return localStorage.getItem(VIEW_STORAGE_KEY) === 'cards' ? 'cards' : 'table';
  } catch {
    return 'table';
  }
}

function matchesRole(review: DocumentSummary, filter: RoleFilter, userId: string | null): boolean {
  return filter === 'all' || roleOf(review, userId) === filter;
}

function matchesStatus(review: DocumentSummary, filter: StatusFilter): boolean {
  if (filter === 'all') return true;
  return isOpenWorkflowState(review.workflowState) === (filter === 'open');
}

function matchesSearch(review: DocumentSummary, query: string): boolean {
  return query === '' || review.title.toLowerCase().includes(query);
}

function sortReviews(reviews: DocumentSummary[], sortBy: SortBy): DocumentSummary[] {
  const sorted = [...reviews];
  if (sortBy === 'name') {
    sorted.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
  } else if (sortBy === 'due') {
    // Soonest deadline (and anything already overdue) first; reviews without a
    // due date sort last so the ones with a clock take priority.
    sorted.sort((a, b) => {
      if (!a.dueAt && !b.dueAt) return 0;
      if (!a.dueAt) return 1;
      if (!b.dueAt) return -1;
      return a.dueAt.localeCompare(b.dueAt);
    });
  } else {
    sorted.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }
  return sorted;
}

function FilterChip({
  label,
  count,
  selected,
  onClick,
}: {
  label: string;
  count: number;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <Chip
      label={`${label} (${count})`}
      size="small"
      color={selected ? 'primary' : 'default'}
      variant={selected ? 'filled' : 'outlined'}
      onClick={onClick}
      sx={{ fontWeight: selected ? 600 : 400 }}
    />
  );
}

/** Reviews overview (#251): every review the user owns or participates in. */
export function ReviewsPage() {
  const navigate = useNavigate();
  const userId = useAuthStore((s) => s.userId);
  const { data, isPending, isError, refetch } = useReviews({
    page: 0,
    size: FETCH_SIZE,
    sort: 'updatedAt,desc',
  });

  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortBy, setSortBy] = useState<SortBy>('updated');
  const [view, setView] = useState<ViewMode>(readStoredView);

  const items = useMemo(() => data?.items ?? [], [data]);
  const query = search.trim().toLowerCase();

  // Faceted counts: each chip group is counted against the search + the OTHER
  // group's filter, so a chip's number always predicts what clicking it shows.
  const searched = useMemo(() => items.filter((r) => matchesSearch(r, query)), [items, query]);
  const roleCounts = useMemo(() => {
    const base = searched.filter((r) => matchesStatus(r, statusFilter));
    return {
      all: base.length,
      owner: base.filter((r) => roleOf(r, userId) === 'owner').length,
      reviewer: base.filter((r) => roleOf(r, userId) === 'reviewer').length,
    };
  }, [searched, statusFilter, userId]);
  const statusCounts = useMemo(() => {
    const base = searched.filter((r) => matchesRole(r, roleFilter, userId));
    return {
      all: base.length,
      open: base.filter((r) => isOpenWorkflowState(r.workflowState)).length,
      closed: base.filter((r) => !isOpenWorkflowState(r.workflowState)).length,
    };
  }, [searched, roleFilter, userId]);

  const visible = useMemo(
    () =>
      sortReviews(
        searched.filter(
          (r) => matchesRole(r, roleFilter, userId) && matchesStatus(r, statusFilter),
        ),
        sortBy,
      ),
    [searched, roleFilter, statusFilter, sortBy, userId],
  );

  const hasActiveFilters = query !== '' || roleFilter !== 'all' || statusFilter !== 'all';

  const changeView = (next: ViewMode | null) => {
    if (!next) return;
    setView(next);
    try {
      localStorage.setItem(VIEW_STORAGE_KEY, next);
    } catch {
      // View preference is a nicety; private-mode storage failures are fine.
    }
  };

  const clearFilters = () => {
    setSearch('');
    setRoleFilter('all');
    setStatusFilter('all');
  };

  const openReview = (documentId: string) => navigate(`/reviews/${documentId}`);

  const newReviewButton = (
    <Button
      variant="contained"
      startIcon={<Plus size={16} />}
      onClick={() => navigate('/reviews/new')}
    >
      New review
    </Button>
  );

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Reviews"
        description="Documents you own or review — pick one up where it stands."
        action={newReviewButton}
      />

      {isError && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => refetch()}>
              Retry
            </Button>
          }
        >
          Reviews could not be loaded.
        </Alert>
      )}

      {isPending && !isError && (
        <Stack spacing={1.5} data-testid="reviews-loading">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} variant="rounded" height={56} />
          ))}
        </Stack>
      )}

      {data && items.length === 0 && (
        <ReviewsEmptyState onNewReview={() => navigate('/reviews/new')} />
      )}

      {data && items.length > 0 && (
        <>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={2}
            sx={{ alignItems: { md: 'center' } }}
          >
            <TextField
              size="small"
              placeholder="Search reviews…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              sx={{ width: { xs: '100%', md: 280 } }}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <Search size={16} />
                    </InputAdornment>
                  ),
                  endAdornment: search ? (
                    <InputAdornment position="end">
                      <IconButton
                        aria-label="Clear search"
                        size="small"
                        edge="end"
                        onClick={() => setSearch('')}
                      >
                        <X size={16} />
                      </IconButton>
                    </InputAdornment>
                  ) : undefined,
                },
              }}
            />
            <Box sx={{ flex: 1 }} />
            <TextField
              select
              size="small"
              label="Sort"
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as SortBy)}
              sx={{ width: 180 }}
            >
              <MenuItem value="updated">Recently updated</MenuItem>
              <MenuItem value="name">Name</MenuItem>
              <MenuItem value="due">Due date</MenuItem>
            </TextField>
            <ToggleButtonGroup
              exclusive
              size="small"
              value={view}
              onChange={(_e, next: ViewMode | null) => changeView(next)}
              aria-label="View mode"
            >
              <ToggleButton value="table" aria-label="Table view">
                <Rows3 size={16} />
              </ToggleButton>
              <ToggleButton value="cards" aria-label="Card view">
                <LayoutGrid size={16} />
              </ToggleButton>
            </ToggleButtonGroup>
          </Stack>

          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            <FilterChip
              label="All"
              count={roleCounts.all}
              selected={roleFilter === 'all'}
              onClick={() => setRoleFilter('all')}
            />
            <FilterChip
              label="Owned by me"
              count={roleCounts.owner}
              selected={roleFilter === 'owner'}
              onClick={() => setRoleFilter('owner')}
            />
            <FilterChip
              label="Reviewing"
              count={roleCounts.reviewer}
              selected={roleFilter === 'reviewer'}
              onClick={() => setRoleFilter('reviewer')}
            />
            <Box sx={{ width: 8 }} />
            <FilterChip
              label="Open"
              count={statusCounts.open}
              selected={statusFilter === 'open'}
              onClick={() => setStatusFilter(statusFilter === 'open' ? 'all' : 'open')}
            />
            <FilterChip
              label="Closed"
              count={statusCounts.closed}
              selected={statusFilter === 'closed'}
              onClick={() => setStatusFilter(statusFilter === 'closed' ? 'all' : 'closed')}
            />
          </Stack>

          {visible.length === 0 ? (
            <Paper variant="outlined" sx={{ py: 6, px: 3, textAlign: 'center' }}>
              <Typography color="text.secondary">No reviews match your filters.</Typography>
              {hasActiveFilters && (
                <Button size="small" onClick={clearFilters} sx={{ mt: 1.5 }}>
                  Clear filters
                </Button>
              )}
            </Paper>
          ) : view === 'table' ? (
            <ReviewsTable reviews={visible} userId={userId} onOpen={openReview} />
          ) : (
            <ReviewCards reviews={visible} userId={userId} onOpen={openReview} />
          )}
        </>
      )}
    </Stack>
  );
}
