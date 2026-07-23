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

import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import Typography from '@mui/material/Typography';
import { Lock, SearchX, UsersRound } from 'lucide-react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import {
  SEARCH_MIN_LENGTH,
  useSearchQuick,
  useSearchReviews,
  useSearchTeams,
  useSearchUsers,
} from '../../api/hooks/useSearch';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { PersonLink } from '../../components/dashboard/PersonLink';
import { WorkflowMilestones } from '../../components/reviews/WorkflowMilestones';

/** The result types the page can list; kept in the URL as `type`. */
const RESULT_TYPES = ['reviews', 'users', 'teams'] as const;
type ResultType = (typeof RESULT_TYPES)[number];

function parseType(raw: string | null): ResultType {
  return (RESULT_TYPES as readonly string[]).includes(raw ?? '') ? (raw as ResultType) : 'reviews';
}

/**
 * The full global-search results (issue #540, ADR-0047): the query, the
 * active type and the page all live in the URL, so a result set is shareable.
 * The type chips are counted from the quick search — a chip's number always
 * predicts what clicking it shows; the list itself pages through the typed
 * endpoint. Review hits carry their milestone path (the #568 state language),
 * people render as the app-wide PersonLink, team hits are linked only when
 * the caller may open the roster.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const type = parseType(params.get('type'));
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  // The box edits locally and lands in the URL debounced, resetting the page.
  const [input, setInput] = useState(q);
  // An outside URL change (back button, dropdown hand-off) re-seeds the box —
  // the render-time derived-state pattern, as in FocusAnnotationCard.
  const [lastQ, setLastQ] = useState(q);
  if (q !== lastQ) {
    setLastQ(q);
    setInput(q);
  }
  useEffect(() => {
    const handle = setTimeout(() => {
      if (input.trim() === q) return;
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          if (input.trim()) next.set('q', input.trim());
          else next.delete('q');
          next.delete('page');
          return next;
        },
        { replace: true },
      );
    }, 300);
    return () => clearTimeout(handle);
  }, [input, q, setParams]);

  const setType = (next: ResultType) => {
    setParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.set('type', next);
      nextParams.delete('page');
      return nextParams;
    });
  };
  const setPage = (next: number) => {
    setParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (next > 0) nextParams.set('page', String(next));
      else nextParams.delete('page');
      return nextParams;
    });
  };

  const quick = useSearchQuick(q);
  const reviews = useSearchReviews(q, page, type === 'reviews');
  const users = useSearchUsers(q, page, type === 'users');
  const teams = useSearchTeams(q, page, type === 'teams');
  const active = type === 'reviews' ? reviews : type === 'users' ? users : teams;
  const total = active.data?.total ?? 0;
  const size = active.data?.size ?? 20;
  const tooShort = q.trim().length < SEARCH_MIN_LENGTH;

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Search"
        description="Reviews, people and teams across your workspace — scoped to what you may see."
      />

      <ClearableSearchField
        value={input}
        onValueChange={setInput}
        placeholder="Search reviews, people and teams…"
        inputAriaLabel="Search reviews, people and teams"
        sx={{ maxWidth: 520 }}
      />

      {tooShort ? (
        <Typography color="text.secondary" data-testid="search-too-short">
          Type at least {SEARCH_MIN_LENGTH} characters to search.
        </Typography>
      ) : (
        <>
          <Stack direction="row" spacing={1}>
            <TypeChip
              label="Reviews"
              count={quick.data?.reviews.total}
              selected={type === 'reviews'}
              onClick={() => setType('reviews')}
            />
            <TypeChip
              label="People"
              count={quick.data?.users.total}
              selected={type === 'users'}
              onClick={() => setType('users')}
            />
            <TypeChip
              label="Teams"
              count={quick.data?.teams.total}
              selected={type === 'teams'}
              onClick={() => setType('teams')}
            />
          </Stack>

          <SectionCard
            title={`Results for “${q.trim()}”`}
            description="Every hit deep-links to its page."
          >
            {active.isPending ? (
              <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                Searching…
              </Typography>
            ) : active.isError ? (
              <Typography color="error" sx={{ py: 3, textAlign: 'center' }}>
                Search is unavailable right now.
              </Typography>
            ) : total === 0 ? (
              <Stack spacing={1} sx={{ py: 4, alignItems: 'center' }} data-testid="search-empty">
                <SearchX size={24} aria-hidden />
                <Typography color="text.secondary">No matches in this group.</Typography>
              </Stack>
            ) : (
              <Stack divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}>
                {type === 'reviews' &&
                  reviews.data?.items.map((hit) => (
                    <Stack
                      key={hit.id}
                      direction="row"
                      spacing={1.5}
                      data-testid="search-row-review"
                      sx={{ alignItems: 'center', py: 1.25 }}
                    >
                      <WorkflowMilestones state={hit.workflowState} />
                      <Link
                        component={RouterLink}
                        to={`/reviews/${hit.slug ?? hit.id}`}
                        underline="hover"
                        sx={{ fontWeight: 600, fontSize: 14, minWidth: 0 }}
                        noWrap
                      >
                        {hit.title}
                      </Link>
                    </Stack>
                  ))}
                {type === 'users' &&
                  users.data?.items.map((hit) => (
                    <Box key={hit.userId} data-testid="search-row-user" sx={{ py: 1.25 }}>
                      <PersonLink
                        userId={hit.userId}
                        slug={hit.slug}
                        name={hit.displayName}
                        avatarUrl={hit.avatarUrl}
                        size={30}
                      />
                    </Box>
                  ))}
                {type === 'teams' &&
                  teams.data?.items.map((hit) => (
                    <Stack
                      key={hit.teamId}
                      direction="row"
                      spacing={1.5}
                      data-testid="search-row-team"
                      sx={{ alignItems: 'center', py: 1.25 }}
                    >
                      <UsersRound size={18} aria-hidden />
                      {hit.viewable ? (
                        <Link
                          component={RouterLink}
                          to={`/my-teams/${hit.slug ?? hit.teamId}`}
                          underline="hover"
                          sx={{ fontWeight: 600, fontSize: 14 }}
                        >
                          {hit.name}
                        </Link>
                      ) : (
                        <>
                          <Typography sx={{ fontWeight: 600, fontSize: 14 }}>{hit.name}</Typography>
                          <Stack
                            direction="row"
                            spacing={0.5}
                            sx={{ alignItems: 'center', color: 'text.disabled' }}
                          >
                            <Lock size={12} aria-hidden />
                            <Typography sx={{ fontSize: 12 }}>Not a member</Typography>
                          </Stack>
                        </>
                      )}
                    </Stack>
                  ))}
              </Stack>
            )}
            {total > size && (
              <TablePagination
                component="div"
                count={total}
                page={page}
                onPageChange={(_e, next) => setPage(next)}
                rowsPerPage={size}
                rowsPerPageOptions={[size]}
              />
            )}
          </SectionCard>
        </>
      )}
    </Stack>
  );
}

/** A counted type facet — the number always predicts what clicking shows. */
function TypeChip({
  label,
  count,
  selected,
  onClick,
}: {
  label: string;
  count: number | undefined;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <Chip
      label={count === undefined ? label : `${label} (${count})`}
      size="small"
      color={selected ? 'primary' : 'default'}
      variant={selected ? 'filled' : 'outlined'}
      onClick={onClick}
      sx={{ fontWeight: selected ? 600 : 400 }}
    />
  );
}
