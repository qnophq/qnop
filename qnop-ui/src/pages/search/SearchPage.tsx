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
import ButtonBase from '@mui/material/ButtonBase';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import {
  FileText,
  Lock,
  MessageSquareText,
  NotebookPen,
  SearchX,
  Users,
  UsersRound,
} from 'lucide-react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import {
  SEARCH_MIN_LENGTH,
  useSearchAnnotations,
  useSearchComments,
  useSearchQuick,
  useSearchReviews,
  useSearchTeams,
  useSearchUsers,
} from '../../api/hooks/useSearch';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { PersonLink } from '../../components/dashboard/PersonLink';
import { tokens } from '../../theme/tokens';
import { WorkflowMilestones } from '../../components/reviews/WorkflowMilestones';
import { StatusCueIcon } from '../../components/shell/search/SearchDropdownResults';
import { discussionHitPath } from '../../components/shell/search/searchPaths';

/** The result types the page can list; kept in the URL as `type`. */
const RESULT_TYPES = ['reviews', 'annotations', 'comments', 'users', 'teams'] as const;
type ResultType = (typeof RESULT_TYPES)[number];

function parseType(raw: string | null): ResultType {
  return (RESULT_TYPES as readonly string[]).includes(raw ?? '') ? (raw as ResultType) : 'reviews';
}

/**
 * The full global-search results (issue #540, ADR-0047), full width like the
 * other work surfaces: a sticky match scoreboard on the left — the five
 * result types as counted score cards, the #568 gamified language — drives
 * the list on the right. Query, active type and page live in the URL, so a
 * result set is shareable; a card's number always predicts what clicking it
 * shows. Review hits carry their milestone path, discussion hits their
 * status cue and excerpt, people render as the app-wide PersonLink, team
 * hits are linked only when the caller may open the roster. Rows reveal with
 * one staged rise (compositor-only, off under reduced motion).
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
  const annotations = useSearchAnnotations(q, page, type === 'annotations');
  const commentHits = useSearchComments(q, page, type === 'comments');
  const users = useSearchUsers(q, page, type === 'users');
  const teams = useSearchTeams(q, page, type === 'teams');
  const active =
    type === 'reviews'
      ? reviews
      : type === 'annotations'
        ? annotations
        : type === 'comments'
          ? commentHits
          : type === 'users'
            ? users
            : teams;
  const total = active.data?.total ?? 0;
  const size = active.data?.size ?? 20;
  const tooShort = q.trim().length < SEARCH_MIN_LENGTH;

  // The one deliberate motion moment: result rows rise in with a short
  // stagger. Compositor-only (transform/opacity), off under reduced motion.
  const reveal = (index: number) => ({
    '@keyframes qnopSearchReveal': {
      from: { opacity: 0, transform: 'translateY(6px)' },
      to: { opacity: 1, transform: 'none' },
    },
    animation: 'qnopSearchReveal .26s ease both',
    animationDelay: `${Math.min(index, 12) * 28}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  });

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
        sx={{ maxWidth: 680 }}
      />

      {tooShort ? (
        <Typography color="text.secondary" data-testid="search-too-short">
          Type at least {SEARCH_MIN_LENGTH} characters to search.
        </Typography>
      ) : (
        <Box
          sx={{
            display: 'grid',
            gap: 3,
            gridTemplateColumns: { xs: '1fr', lg: '280px minmax(0, 1fr)' },
            alignItems: 'start',
          }}
        >
          {/* The match scoreboard: every result type keeps its score in view;
              the active card drives the list (issue #540 gamified facets). */}
          <Box
            sx={{
              display: 'flex',
              flexDirection: { xs: 'row', lg: 'column' },
              gap: 1,
              overflowX: { xs: 'auto', lg: 'visible' },
              position: { lg: 'sticky' },
              top: { lg: 88 },
              pb: { xs: 0.5, lg: 0 },
            }}
          >
            <ScoreCard
              icon={FileText}
              label="Reviews"
              count={quick.data?.reviews.total}
              selected={type === 'reviews'}
              onClick={() => setType('reviews')}
            />
            <ScoreCard
              icon={NotebookPen}
              label="Annotations"
              count={quick.data?.annotations.total}
              selected={type === 'annotations'}
              onClick={() => setType('annotations')}
            />
            <ScoreCard
              icon={MessageSquareText}
              label="Comments"
              count={quick.data?.comments.total}
              selected={type === 'comments'}
              onClick={() => setType('comments')}
            />
            <ScoreCard
              icon={Users}
              label="People"
              count={quick.data?.users.total}
              selected={type === 'users'}
              onClick={() => setType('users')}
            />
            <ScoreCard
              icon={UsersRound}
              label="Teams"
              count={quick.data?.teams.total}
              selected={type === 'teams'}
              onClick={() => setType('teams')}
            />
          </Box>

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
              <Stack
                key={`${type}:${q.trim()}:${page}`}
                divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}
              >
                {type === 'reviews' &&
                  reviews.data?.items.map((hit, index) => (
                    <Stack
                      key={hit.id}
                      direction="row"
                      spacing={1.5}
                      data-testid="search-row-review"
                      sx={{ alignItems: 'center', py: 1.25, ...reveal(index) }}
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
                {(type === 'annotations' || type === 'comments') &&
                  (type === 'annotations' ? annotations : commentHits).data?.items.map(
                    (hit, index) => (
                      <Stack
                        key={hit.commentId}
                        direction="row"
                        spacing={1.5}
                        data-testid={`search-row-${type === 'annotations' ? 'annotation' : 'comment'}`}
                        sx={{ alignItems: 'flex-start', py: 1.25, ...reveal(index) }}
                      >
                        <Box sx={{ pt: 0.3, display: 'flex' }}>
                          <StatusCueIcon status={hit.annotationStatus} />
                        </Box>
                        <Box sx={{ minWidth: 0 }}>
                          <Link
                            component={RouterLink}
                            to={discussionHitPath(hit, type === 'comments')}
                            underline="hover"
                            sx={{ fontWeight: 600, fontSize: 14, display: 'block' }}
                            noWrap
                          >
                            {hit.excerpt}
                          </Link>
                          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }} noWrap>
                            in {hit.documentTitle}
                          </Typography>
                        </Box>
                      </Stack>
                    ),
                  )}
                {type === 'users' &&
                  users.data?.items.map((hit, index) => (
                    <Box
                      key={hit.userId}
                      data-testid="search-row-user"
                      sx={{ py: 1.25, ...reveal(index) }}
                    >
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
                  teams.data?.items.map((hit, index) => (
                    <Stack
                      key={hit.teamId}
                      direction="row"
                      spacing={1.5}
                      data-testid="search-row-team"
                      sx={{ alignItems: 'center', py: 1.25, ...reveal(index) }}
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
        </Box>
      )}
    </Stack>
  );
}

/**
 * A scoreboard facet (issue #540): icon, label and the group's match count in
 * the mono score type — the number always predicts what clicking shows. The
 * active card carries the brand accent; color stays semantic, not decorative.
 */
function ScoreCard({
  icon: Icon,
  label,
  count,
  selected,
  onClick,
}: {
  icon: LucideIcon;
  label: string;
  count: number | undefined;
  selected: boolean;
  onClick: () => void;
}) {
  const theme = useTheme();
  return (
    <ButtonBase
      onClick={onClick}
      aria-pressed={selected}
      aria-label={`${label} (${count ?? 0})`}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.25,
        minWidth: { xs: 150, lg: 'auto' },
        width: { lg: '100%' },
        px: 1.75,
        py: 1.25,
        borderRadius: 2.5,
        border: '1px solid',
        borderColor: selected ? 'primary.main' : 'divider',
        bgcolor: selected
          ? alpha(theme.palette.primary.main, theme.palette.mode === 'dark' ? 0.16 : 0.08)
          : 'background.paper',
        textAlign: 'left',
        fontFamily: 'inherit',
        transition: 'border-color .15s, background-color .15s',
        '&:hover': { borderColor: selected ? 'primary.main' : 'text.disabled' },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      <Icon
        size={16}
        aria-hidden
        style={{
          color: selected ? theme.palette.primary.main : theme.palette.text.secondary,
          flexShrink: 0,
        }}
      />
      <Typography sx={{ flex: 1, fontSize: 13.5, fontWeight: selected ? 700 : 500 }}>
        {label}
      </Typography>
      <Typography
        component="span"
        sx={{
          fontFamily: tokens.font.mono,
          fontSize: 15,
          fontWeight: 700,
          color: selected ? 'primary.main' : 'text.secondary',
        }}
      >
        {count ?? '–'}
      </Typography>
    </ButtonBase>
  );
}
