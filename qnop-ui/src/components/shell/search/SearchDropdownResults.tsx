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

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import {
  ArrowRight,
  FileText,
  Lock,
  MessageSquareText,
  NotebookPen,
  SearchX,
  Users,
  UsersRound,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { GlobalSearchResponse } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { useAuthStore } from '../../../stores/authStore';
import { ToneBadge } from '../../admin/ToneBadge';
import { WorkflowStateIcon } from '../../reviews/WorkflowStateIcon';
import { STATUS_CUES } from '../../reviews/panel/statusCues';
import { UserAvatar } from '../UserAvatar';
import { discussionHitPath } from './searchPaths';

/** The results page's type segment for a group's "see all" continuation. */
type ResultType = 'reviews' | 'annotations' | 'comments' | 'users' | 'teams';

/**
 * The quickview body of the global search (issue #540): five counted
 * sections — Reviews with their workflow state glyph (the #568 language at
 * icon scale), Annotations and Comments with their status cue and matched
 * excerpt, People with their avatars, Teams with a lock on hits the caller
 * cannot open. Every row is a real navigation; each group ends in a "see all
 * N" continuation onto the results page when the cap cut it short.
 */
export function SearchDropdownResults({
  query,
  data,
}: {
  query: string;
  data: GlobalSearchResponse;
}) {
  const theme = useTheme();
  const navigate = useNavigate();
  const userId = useAuthStore((s) => s.userId);
  const empty =
    data.reviews.items.length === 0 &&
    data.annotations.items.length === 0 &&
    data.comments.items.length === 0 &&
    data.users.items.length === 0 &&
    data.teams.items.length === 0;

  if (empty) {
    return (
      <Stack spacing={1} sx={{ p: 3, alignItems: 'center' }} data-testid="global-search-empty">
        <SearchX size={22} aria-hidden style={{ color: theme.palette.text.disabled }} />
        <Typography sx={{ fontSize: 13, color: 'text.secondary', textAlign: 'center' }}>
          No matches for “{query}”.
        </Typography>
      </Stack>
    );
  }

  const seeAll = (type: ResultType) =>
    navigate(`/search?q=${encodeURIComponent(query)}&type=${type}`);

  return (
    <Box sx={{ py: 0.75 }}>
      {data.reviews.items.length > 0 && (
        <Section icon={FileText} label="Reviews" total={data.reviews.total}>
          {data.reviews.items.map((hit) => (
            <HitRow
              key={hit.id}
              testId="search-hit-review"
              onClick={() => navigate(`/reviews/${hit.slug ?? hit.id}`)}
              start={<WorkflowStateIcon state={hit.workflowState} size={14} />}
              primary={hit.title}
            />
          ))}
          {data.reviews.total > data.reviews.items.length && (
            <SeeAllRow total={data.reviews.total} onClick={() => seeAll('reviews')} />
          )}
        </Section>
      )}
      {data.annotations.items.length > 0 && (
        <Section icon={NotebookPen} label="Annotations" total={data.annotations.total}>
          {data.annotations.items.map((hit) => (
            <HitRow
              key={hit.commentId}
              testId="search-hit-annotation"
              onClick={() => navigate(discussionHitPath(hit, false))}
              start={<StatusCueIcon status={hit.annotationStatus} />}
              primary={hit.excerpt}
              secondary={`in ${hit.documentTitle}`}
            />
          ))}
          {data.annotations.total > data.annotations.items.length && (
            <SeeAllRow total={data.annotations.total} onClick={() => seeAll('annotations')} />
          )}
        </Section>
      )}
      {data.comments.items.length > 0 && (
        <Section icon={MessageSquareText} label="Comments" total={data.comments.total}>
          {data.comments.items.map((hit) => (
            <HitRow
              key={hit.commentId}
              testId="search-hit-comment"
              onClick={() => navigate(discussionHitPath(hit, true))}
              start={<StatusCueIcon status={hit.annotationStatus} />}
              primary={hit.excerpt}
              secondary={`in ${hit.documentTitle}`}
            />
          ))}
          {data.comments.total > data.comments.items.length && (
            <SeeAllRow total={data.comments.total} onClick={() => seeAll('comments')} />
          )}
        </Section>
      )}
      {data.users.items.length > 0 && (
        <Section icon={Users} label="People" total={data.users.total}>
          {data.users.items.map((hit) => (
            <HitRow
              key={hit.userId}
              testId="search-hit-user"
              onClick={() =>
                navigate(hit.userId === userId ? '/profile' : `/users/${hit.slug ?? hit.userId}`)
              }
              start={<UserAvatar name={hit.displayName} size={22} imageUrl={hit.avatarUrl} />}
              primary={hit.displayName}
            />
          ))}
          {data.users.total > data.users.items.length && (
            <SeeAllRow total={data.users.total} onClick={() => seeAll('users')} />
          )}
        </Section>
      )}
      {data.teams.items.length > 0 && (
        <Section icon={UsersRound} label="Teams" total={data.teams.total}>
          {data.teams.items.map((hit) =>
            hit.viewable ? (
              <HitRow
                key={hit.teamId}
                testId="search-hit-team"
                onClick={() => navigate(`/my-teams/${hit.slug ?? hit.teamId}`)}
                start={<UsersRound size={16} aria-hidden />}
                primary={hit.name}
              />
            ) : (
              // A stranger's team hit is listed, not linked — the roster is
              // member-or-admin-only (issue #470), so no dead 403 clicks.
              <Stack
                key={hit.teamId}
                direction="row"
                spacing={1}
                data-testid="search-hit-team-locked"
                sx={{ alignItems: 'center', px: 2, py: 0.75, color: 'text.disabled' }}
              >
                <UsersRound size={16} aria-hidden />
                <Typography sx={{ fontSize: 13.5, flex: 1 }} noWrap>
                  {hit.name}
                </Typography>
                <Lock size={12} aria-label="Not a member" />
              </Stack>
            ),
          )}
          {data.teams.total > data.teams.items.length && (
            <SeeAllRow total={data.teams.total} onClick={() => seeAll('teams')} />
          )}
        </Section>
      )}
    </Box>
  );
}

/** The annotation-status cue of a discussion hit — the panel's #405 language at icon scale. */
export function StatusCueIcon({ status }: { status: AnnotationStatus }) {
  const theme = useTheme();
  const cue = STATUS_CUES[status] ?? STATUS_CUES[AnnotationStatus.Open];
  const Icon = cue.icon;
  return (
    <Icon size={14} aria-label={cue.label} style={{ color: cue.color(theme), flexShrink: 0 }} />
  );
}

/** A counted group: icon + label leading, the group's full match count as a badge. */
function Section({
  icon: Icon,
  label,
  total,
  children,
}: {
  icon: LucideIcon;
  label: string;
  total: number;
  children: ReactNode;
}) {
  return (
    <Box sx={{ '& + &': { mt: 0.5 } }}>
      <Stack
        direction="row"
        spacing={0.75}
        sx={{ alignItems: 'center', px: 2, pt: 1, pb: 0.5, color: 'text.secondary' }}
      >
        <Icon size={13} aria-hidden />
        <Typography
          component="h3"
          sx={{ fontSize: 11, fontWeight: 700, letterSpacing: 0.6, textTransform: 'uppercase' }}
        >
          {label}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <ToneBadge tone="blue" label={String(total)} />
      </Stack>
      {children}
      <Divider sx={{ mt: 0.75, '&:last-child': { display: 'none' } }} />
    </Box>
  );
}

/** One navigating hit row; `secondary` is the muted match excerpt (issue #540). */
function HitRow({
  testId,
  onClick,
  start,
  primary,
  secondary,
}: {
  testId: string;
  onClick: () => void;
  start: ReactNode;
  primary: string;
  secondary?: string;
}) {
  return (
    <ButtonBase
      onClick={onClick}
      data-testid={testId}
      sx={{
        display: 'flex',
        justifyContent: 'flex-start',
        alignItems: secondary ? 'flex-start' : 'center',
        gap: 1,
        width: '100%',
        px: 2,
        py: 0.75,
        textAlign: 'left',
        fontFamily: 'inherit',
        '&:hover, &:focus-visible': { bgcolor: (t) => t.qnop.surface2 },
      }}
    >
      <Box sx={{ pt: secondary ? 0.4 : 0, display: 'flex', flexShrink: 0 }}>{start}</Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 13.5 }} noWrap>
          {primary}
        </Typography>
        {secondary && (
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }} noWrap>
            {secondary}
          </Typography>
        )}
      </Box>
    </ButtonBase>
  );
}

/** The group's continuation onto the results page when the cap cut it short. */
function SeeAllRow({ total, onClick }: { total: number; onClick: () => void }) {
  return (
    <ButtonBase
      onClick={onClick}
      data-testid="search-see-all"
      sx={{
        display: 'flex',
        justifyContent: 'flex-start',
        gap: 0.75,
        width: '100%',
        px: 2,
        py: 0.5,
        fontFamily: 'inherit',
        color: 'primary.main',
        fontSize: 12.5,
        fontWeight: 600,
        '&:hover, &:focus-visible': { bgcolor: (t) => t.qnop.surface2 },
      }}
    >
      See all {total} results
      <ArrowRight size={13} aria-hidden />
    </ButtonBase>
  );
}
