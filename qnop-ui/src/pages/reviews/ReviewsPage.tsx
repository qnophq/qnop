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

import { useEffect, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Badge from '@mui/material/Badge';
import IconButton from '@mui/material/IconButton';
import ListSubheader from '@mui/material/ListSubheader';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { LayoutGrid, ListFilter, Plus, Rows3 } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router';
import type { DocumentSummary } from '../../api/generated';
import { useReviews } from '../../api/hooks/useReviews';
import { ErrorState } from '../errors/ErrorState';
import { ServerErrorIllustration } from '../errors/illustrations';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { isOpenWorkflowState, workflowLabel } from '../../components/reviews/workflowMeta';
import { roleOf } from '../../components/reviews/list/reviewListModel';
import { ReviewCards } from '../../components/reviews/list/ReviewCards';
import { ReviewsTable } from '../../components/reviews/list/ReviewsTable';
import { ReviewsEmptyState } from './ReviewsEmptyState';
import { selectIsAdmin, useAuthStore } from '../../stores/authStore';

type RoleFilter = 'all' | 'owner' | 'reviewer';
// Client facets over ONE scope=all fetch (issue #576 follow-up), so every chip
// carries a count without a refetch. Archiving is a retention flag, not a
// workflow state (#576): the default 'active' facet spans every workflow state
// but stops at the archive line (issue #578 — a fresh visit never surfaces cold
// records), while 'all' ("Every state") and 'archived' are the two explicit
// ways in — 'all' truly shows everything at once, archive included.
// All facets are URL-persisted; no param means the default, archived hidden.
type StatusFilter = 'active' | 'all' | 'open' | 'closed' | 'archived';
type DueFilter = 'any' | 'overdue' | 'soon' | 'none';
type FormatFilter = 'any' | 'pdf' | 'docx' | 'md';
type SortBy = 'updated' | 'name' | 'due';
type ViewMode = 'table' | 'cards';

const VIEW_STORAGE_KEY = 'qnop-reviews-view';
// The overview loads one big page and filters client-side so the chip counters
// stay consistent with what is on screen; server paging arrives when real
// installations outgrow this (documented trade-off in #251).
const FETCH_SIZE = 100;

/**
 * The moderation listing pages on the server (issue #563).
 *
 * <p>The participant-scoped view fetches once and facets in the browser, which is
 * honest as long as the caller's own reviews fit in one page. Across a whole
 * workspace they do not, and a moderation view that quietly stopped at the first
 * hundred would be the worst kind of wrong: seeing everything is the point.
 */
const MODERATION_PAGE_SIZE = 20;

/** Mirrors the admin lists' debounce — one query per typing pause. */
const SEARCH_DEBOUNCE_MS = 300;

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
  const archived = Boolean(review.archivedAt);
  // Only the explicit facets cross the archive line (issue #578): 'all' spans
  // everything at once, 'archived' shows only the records; the default
  // 'active' and the open/closed slices stay on this side of it.
  if (filter === 'all') return true;
  if (filter === 'archived') return archived;
  if (archived) return false;
  if (filter === 'active') return true;
  return isOpenWorkflowState(review.workflowState) === (filter === 'open');
}

/** MIME family for the format facet — the DocumentIcon's ribbon language. */
function formatOf(contentType: string | null | undefined): FormatFilter | 'other' {
  const mime = contentType?.split(';')[0].trim().toLowerCase() ?? '';
  if (mime === 'application/pdf') return 'pdf';
  if (mime.includes('wordprocessingml') || mime === 'application/msword') return 'docx';
  if (mime === 'text/markdown') return 'md';
  return 'other';
}

const DUE_SOON_DAYS = 7;

/**
 * The due facet speaks about LIVING deadlines: overdue/soon apply to open,
 * non-archived reviews only (a finalized review is never "overdue"); 'none'
 * finds work nobody has put a clock on yet.
 */
function matchesDue(review: DocumentSummary, filter: DueFilter, now: number): boolean {
  if (filter === 'any') return true;
  if (filter === 'none') return !review.dueAt;
  if (!review.dueAt || review.archivedAt || !isOpenWorkflowState(review.workflowState)) {
    return false;
  }
  const due = Date.parse(review.dueAt);
  if (filter === 'overdue') return due < now;
  return due >= now && due <= now + DUE_SOON_DAYS * 24 * 60 * 60 * 1000;
}

const STATUS_FILTERS: readonly StatusFilter[] = ['active', 'all', 'open', 'closed', 'archived'];
const ROLE_FILTERS: readonly RoleFilter[] = ['all', 'owner', 'reviewer'];
const DUE_FILTERS: readonly DueFilter[] = ['any', 'overdue', 'soon', 'none'];
// The fine-grained workflow facet (open string set, ADR-0011 — the known states).
const STATE_FILTERS = [
  'any',
  'DRAFT',
  'IN_REVIEW',
  'CHANGES_REQUESTED',
  'FINALIZED',
  'CANCELLED',
] as const;
type StateFilter = (typeof STATE_FILTERS)[number];
const FORMAT_FILTERS: readonly FormatFilter[] = ['any', 'pdf', 'docx', 'md'];

/** Parses a URL facet param against its allowed set, falling back to the default. */
function parseParam<T extends string>(raw: string | null, allowed: readonly T[], fallback: T): T {
  return allowed.includes(raw as T) ? (raw as T) : fallback;
}

/** Search matches the title AND the owner's name — "everything of Anna's" in one keystroke. */
function matchesSearch(review: DocumentSummary, query: string): boolean {
  return (
    query === '' ||
    review.title.toLowerCase().includes(query) ||
    (review.ownerDisplayName ?? '').toLowerCase().includes(query)
  );
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
  hint,
  selected,
  onClick,
}: {
  label: string;
  count?: number;
  /** One-line explanation of what the facet shows, surfaced on hover. */
  hint: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <Tooltip title={hint} arrow>
      <Chip
        label={count === undefined ? label : `${label} (${count})`}
        size="small"
        color={selected ? 'primary' : 'default'}
        variant={selected ? 'filled' : 'outlined'}
        onClick={onClick}
        sx={{ fontWeight: selected ? 600 : 400 }}
      />
    </Tooltip>
  );
}

const DUE_LABELS: Record<DueFilter, string> = {
  any: 'Any due date',
  overdue: 'Overdue',
  soon: `Due in ${DUE_SOON_DAYS} days`,
  none: 'No due date',
};

const FORMAT_LABELS: Record<FormatFilter, string> = {
  any: 'Any format',
  pdf: 'PDF',
  docx: 'Word (DOCX)',
  md: 'Markdown',
};

/**
 * The secondary facets behind one quiet filter button (the annotation panel's
 * pattern): due date, workflow state, format and owner. The primary journey
 * stays on the chips; the menu carries the sharper questions — "what is
 * overdue?", "where are changes requested?", "everything Anna owns".
 */
function ReviewFilterMenu({
  dueFilter,
  formatFilter,
  stateFilter,
  ownerFilter,
  owners,
  activeCount,
  onSet,
}: {
  dueFilter: DueFilter;
  formatFilter: FormatFilter;
  stateFilter: StateFilter;
  ownerFilter: string | null;
  owners: [string, string][];
  activeCount: number;
  onSet: (key: string, next: string, fallback: string) => void;
}) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const pick = (key: string, next: string, fallback: string) => {
    onSet(key, next, fallback);
    setAnchorEl(null);
  };
  return (
    <>
      <Badge badgeContent={activeCount} color="primary" overlap="circular">
        <IconButton
          aria-label="Filter reviews"
          aria-haspopup="menu"
          aria-expanded={Boolean(anchorEl)}
          onClick={(e) => setAnchorEl(e.currentTarget)}
          size="small"
          sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1.5 }}
        >
          <ListFilter size={16} />
        </IconButton>
      </Badge>
      <Menu
        open={Boolean(anchorEl)}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        slotProps={{ list: { dense: true, 'aria-label': 'Review filters' } }}
      >
        <ListSubheader disableSticky>Due date</ListSubheader>
        {DUE_FILTERS.map((value) => (
          <MenuItem
            key={value}
            selected={dueFilter === value}
            onClick={() => pick('due', value, 'any')}
          >
            {DUE_LABELS[value]}
          </MenuItem>
        ))}
        <ListSubheader disableSticky>Workflow state</ListSubheader>
        {STATE_FILTERS.map((value) => (
          <MenuItem
            key={value}
            selected={stateFilter === value}
            onClick={() => pick('state', value, 'any')}
          >
            {value === 'any' ? 'Any state' : workflowLabel(value)}
          </MenuItem>
        ))}
        <ListSubheader disableSticky>Format</ListSubheader>
        {FORMAT_FILTERS.map((value) => (
          <MenuItem
            key={value}
            selected={formatFilter === value}
            onClick={() => pick('format', value, 'any')}
          >
            {FORMAT_LABELS[value]}
          </MenuItem>
        ))}
        <ListSubheader disableSticky>Owner</ListSubheader>
        <MenuItem selected={ownerFilter === null} onClick={() => pick('owner', '', '')}>
          Anyone
        </MenuItem>
        {owners.map(([id, name]) => (
          <MenuItem key={id} selected={ownerFilter === id} onClick={() => pick('owner', id, '')}>
            {name}
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}

/** Reviews overview (#251): every review the user owns or participates in. */
export function ReviewsPage() {
  const navigate = useNavigate();
  const userId = useAuthStore((s) => s.userId);
  const isAdmin = useAuthStore(selectIsAdmin);

  // Every facet is URL-persisted (issue #576 follow-up) so any sliced view is
  // shareable and survives reloads. ONE scope=all fetch backs them all: the
  // facets are pure client slices, so chip counts always agree with the rows
  // and switching a facet never refetches.
  const [searchParams, setSearchParams] = useSearchParams();
  const setParam = (key: string, next: string, fallback: string) =>
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next === fallback) p.delete(key);
        else p.set(key, next);
        return p;
      },
      { replace: true },
    );
  const statusFilter = parseParam(searchParams.get('status'), STATUS_FILTERS, 'active');
  const roleFilter = parseParam(searchParams.get('role'), ROLE_FILTERS, 'all');
  const dueFilter = parseParam(searchParams.get('due'), DUE_FILTERS, 'any');
  const formatFilter = parseParam(searchParams.get('format'), FORMAT_FILTERS, 'any');
  const stateFilter = parseParam(searchParams.get('state'), STATE_FILTERS, 'any');
  const ownerFilter = searchParams.get('owner');
  // The admin's moderation listing (issue #563). Opt-in and URL-persisted like
  // the rest, default off so an admin's own work is not drowned out.
  const moderating = isAdmin && searchParams.get('participation') === 'all';
  const [serverPage, setServerPage] = useState(0);
  // Moderation searches the SERVER, so it waits for a pause in typing the way the
  // admin lists do; the participant-scoped view keeps filtering as you type
  // because it already holds its rows.
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const setStatusFilter = (next: StatusFilter) => setParam('status', next, 'active');
  const setRoleFilter = (next: RoleFilter) => setParam('role', next, 'all');

  const { data, isPending, isError, refetch } = useReviews(
    moderating
      ? {
          page: serverPage,
          size: MODERATION_PAGE_SIZE,
          sort: 'updatedAt,desc',
          scope: 'all',
          participation: 'all',
          q: debouncedQuery,
        }
      : { page: 0, size: FETCH_SIZE, sort: 'updatedAt,desc', scope: 'all' },
  );

  const [search, setSearch] = useState(searchParams.get('q') ?? '');
  const setSearchAndParam = (next: string) => {
    setSearch(next);
    setParam('q', next.trim(), '');
  };
  useEffect(() => {
    if (!moderating) return;
    const timer = setTimeout(() => {
      setDebouncedQuery(search.trim());
      setServerPage(0); // a new query starts at its own first page
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search, moderating]);

  const setModerating = (next: boolean) => {
    setServerPage(0);
    setDebouncedQuery(search.trim());
    setParam('participation', next ? 'all' : 'mine', 'mine');
  };
  const [sortBy, setSortBy] = useState<SortBy>('updated');
  const [view, setView] = useState<ViewMode>(readStoredView);
  // One clock per mount keeps the due facet stable across rows (and pure for
  // the React Compiler); a list view does not need a ticking deadline.
  const [now] = useState(() => Date.now());

  const matchesAdvanced = (review: DocumentSummary) =>
    matchesDue(review, dueFilter, now) &&
    (formatFilter === 'any' || formatOf(review.contentType) === formatFilter) &&
    (stateFilter === 'any' || review.workflowState === stateFilter) &&
    (ownerFilter === null || review.ownerId === ownerFilter);
  const advancedCount =
    (dueFilter !== 'any' ? 1 : 0) +
    (formatFilter !== 'any' ? 1 : 0) +
    (stateFilter !== 'any' ? 1 : 0) +
    (ownerFilter ? 1 : 0);

  const items = useMemo(() => data?.items ?? [], [data]);
  const query = search.trim().toLowerCase();

  // Faceted counts: each chip group is counted against the search + the OTHER
  // group's filter, so a chip's number always predicts what clicking it shows.
  const searched = items.filter((r) => matchesSearch(r, query) && matchesAdvanced(r));
  const roleCounts = (() => {
    const base = searched.filter((r) => matchesStatus(r, statusFilter));
    return {
      all: base.length,
      owner: base.filter((r) => roleOf(r, userId) === 'owner').length,
      reviewer: base.filter((r) => roleOf(r, userId) === 'reviewer').length,
    };
  })();
  const statusCounts = (() => {
    const base = searched.filter((r) => matchesRole(r, roleFilter, userId));
    // Every count goes through the SAME predicate the list uses, so a chip's
    // number keeps predicting what clicking it shows — 'all' counts the archive
    // in, the default 'active' and open/closed stop at the archive line.
    return {
      active: base.filter((r) => matchesStatus(r, 'active')).length,
      all: base.filter((r) => matchesStatus(r, 'all')).length,
      open: base.filter((r) => matchesStatus(r, 'open')).length,
      closed: base.filter((r) => matchesStatus(r, 'closed')).length,
      archived: base.filter((r) => matchesStatus(r, 'archived')).length,
    };
  })();

  // Moderation rows arrive already filtered and paged by the server, so the client
  // facets are neither applied nor offered: applied, they would narrow one page
  // and read as the whole workspace.
  const visible = moderating
    ? sortReviews(items, sortBy)
    : sortReviews(
        searched.filter(
          (r) => matchesRole(r, roleFilter, userId) && matchesStatus(r, statusFilter),
        ),
        sortBy,
      );

  // A workspace whose living work is all done holds nothing but records. Saying
  // "nothing matches your filters" there would be a dead end — no filter is set
  // and none can be cleared — so the archive gets named and offered instead
  // (issue #578); the opt-in stays a deliberate click either way.
  const onlyArchivedLeft =
    visible.length === 0 &&
    statusFilter !== 'archived' &&
    statusFilter !== 'all' &&
    statusCounts.archived > 0;

  // The owner facet offers everyone who owns a loaded review, alphabetically.
  const owners = (() => {
    const byId = new Map<string, string>();
    for (const r of items) {
      if (r.ownerId && !byId.has(r.ownerId)) byId.set(r.ownerId, r.ownerDisplayName ?? 'Unknown');
    }
    return [...byId].sort((a, b) => a[1].localeCompare(b[1]));
  })();

  const hasActiveFilters =
    query !== '' || roleFilter !== 'all' || statusFilter !== 'active' || advancedCount > 0;

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
    setSearchParams(new URLSearchParams(), { replace: true });
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
        description={
          moderating
            ? 'Every review in the workspace — you are moderating, not participating.'
            : 'Documents you own or review — pick one up where it stands.'
        }
        action={newReviewButton}
      />

      {isAdmin && (
        <ToggleButtonGroup
          exclusive
          size="small"
          value={moderating ? 'all' : 'mine'}
          onChange={(_e, next: string | null) => next && setModerating(next === 'all')}
          aria-label="Which reviews"
        >
          <ToggleButton value="mine" data-testid="participation-mine">
            My reviews
          </ToggleButton>
          <ToggleButton value="all" data-testid="participation-all">
            All reviews
          </ToggleButton>
        </ToggleButtonGroup>
      )}

      {isError && (
        // The branded failure shell (issue #611) instead of a bare alert -
        // same voice as the full-page states: our fault, with a way onward.
        <ErrorState
          title="Your reviews didn't make it to the desk"
          message="Loading failed on our side - not your doing. Give it another try; if it keeps happening, your admin will want to know."
          illustration={<ServerErrorIllustration />}
          tone="alert"
          primaryAction={{ label: 'Try again', onClick: () => refetch() }}
          secondaryAction={{ label: 'Back to dashboard', to: '/' }}
        />
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
            <ClearableSearchField
              placeholder="Search by title or owner…"
              value={search}
              onValueChange={setSearchAndParam}
              sx={{ width: { xs: '100%', md: 280 } }}
            />
            <Box sx={{ flex: 1 }} />
            {!moderating && (
              <ReviewFilterMenu
                dueFilter={dueFilter}
                formatFilter={formatFilter}
                stateFilter={stateFilter}
                ownerFilter={ownerFilter}
                owners={owners}
                activeCount={advancedCount}
                onSet={setParam}
              />
            )}
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

          {!moderating && (
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
              <FilterChip
                label="All"
                hint="Every review you can see, whatever your part in it"
                count={roleCounts.all}
                selected={roleFilter === 'all'}
                onClick={() => setRoleFilter('all')}
              />
              <FilterChip
                label="Owned by me"
                hint="Reviews you own and steer"
                count={roleCounts.owner}
                selected={roleFilter === 'owner'}
                onClick={() => setRoleFilter('owner')}
              />
              <FilterChip
                label="Reviewing"
                hint="Reviews where you are on the reviewer roster"
                count={roleCounts.reviewer}
                selected={roleFilter === 'reviewer'}
                onClick={() => setRoleFilter('reviewer')}
              />
              <Box sx={{ width: 8 }} />
              <FilterChip
                label="Active"
                hint="All live work — every workflow state except archived records (the default)"
                count={statusCounts.active}
                selected={statusFilter === 'active'}
                onClick={() => setStatusFilter('active')}
              />
              <FilterChip
                label="Open"
                hint="Live reviews the workflow has not closed yet"
                count={statusCounts.open}
                selected={statusFilter === 'open'}
                onClick={() => setStatusFilter(statusFilter === 'open' ? 'active' : 'open')}
              />
              <FilterChip
                label="Closed"
                hint="Finalized or cancelled reviews that are not archived yet"
                count={statusCounts.closed}
                selected={statusFilter === 'closed'}
                onClick={() => setStatusFilter(statusFilter === 'closed' ? 'active' : 'closed')}
              />
              <FilterChip
                label="Archived"
                hint="Only the records archived out of the active lists"
                count={statusCounts.archived}
                selected={statusFilter === 'archived'}
                onClick={() => setStatusFilter(statusFilter === 'archived' ? 'active' : 'archived')}
              />
              <FilterChip
                label="Every state"
                hint="Everything at once — every workflow state, archived records included"
                count={statusCounts.all}
                selected={statusFilter === 'all'}
                onClick={() => setStatusFilter(statusFilter === 'all' ? 'active' : 'all')}
              />
              {hasActiveFilters && (
                <Chip
                  label="Clear filters"
                  size="small"
                  variant="outlined"
                  onDelete={clearFilters}
                  onClick={clearFilters}
                  sx={{ ml: 'auto' }}
                />
              )}
            </Stack>
          )}

          {visible.length === 0 ? (
            <Paper variant="outlined" sx={{ py: 6, px: 3, textAlign: 'center' }}>
              <Typography color="text.secondary">
                {onlyArchivedLeft
                  ? 'No active reviews — everything here has been archived.'
                  : 'No reviews match your filters.'}
              </Typography>
              {onlyArchivedLeft && (
                <Button
                  size="small"
                  onClick={() => setStatusFilter('archived')}
                  sx={{ mt: 1.5, mx: 0.5 }}
                >
                  Show archived ({statusCounts.archived})
                </Button>
              )}
              {hasActiveFilters && (
                <Button size="small" onClick={clearFilters} sx={{ mt: 1.5, mx: 0.5 }}>
                  Clear filters
                </Button>
              )}
            </Paper>
          ) : view === 'table' ? (
            <ReviewsTable reviews={visible} userId={userId} onOpen={openReview} />
          ) : (
            <ReviewCards reviews={visible} userId={userId} onOpen={openReview} />
          )}

          {moderating && (data?.total ?? 0) > MODERATION_PAGE_SIZE && (
            <Stack
              direction="row"
              spacing={2}
              sx={{ alignItems: 'center', justifyContent: 'flex-end' }}
            >
              {/* Named counts, not just arrows: a moderator has to know whether
                  they are looking at everything or at page one of twelve. */}
              <Typography variant="body2" color="text.secondary">
                {serverPage * MODERATION_PAGE_SIZE + 1}–
                {Math.min((serverPage + 1) * MODERATION_PAGE_SIZE, data?.total ?? 0)} of{' '}
                {data?.total ?? 0}
              </Typography>
              <Button
                size="small"
                disabled={serverPage === 0}
                onClick={() => setServerPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </Button>
              <Button
                size="small"
                disabled={(serverPage + 1) * MODERATION_PAGE_SIZE >= (data?.total ?? 0)}
                onClick={() => setServerPage((p) => p + 1)}
              >
                Next
              </Button>
            </Stack>
          )}
        </>
      )}
    </Stack>
  );
}
