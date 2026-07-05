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
import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Collapse from '@mui/material/Collapse';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ChevronRight, Link2, NotebookPen, Unlink } from 'lucide-react';
import type {
  AnnotationPriority,
  AnnotationType,
  Anchor,
  AnnotationView,
} from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { useParticipants } from '../../../api/hooks/useReviews';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { SectionCard } from '../../admin/layout/SectionCard';
import { compareAnnotationsByPosition } from '../viewer/anchoring';
import { isUnseen } from '../newSince';
import { AnnotationListItem } from './AnnotationListItem';
import { CommentThread } from './CommentThread';
import { Composer } from './Composer';
import type { FilterAuthor } from './PanelFilterBar';
import { PanelFilterBar } from './PanelFilterBar';
import type { AnnotationFilters } from './panelFilters';
import { EMPTY_FILTERS, matchesFilters } from './panelFilters';
import { ResolveBar } from './ResolveBar';
import {
  mayReopenAnnotation,
  mayResolveAnnotation,
  useReopenWithFeedback,
  useResolveWithFeedback,
} from './resolve';

interface AnnotationPanelProps {
  /** The review the annotations belong to — resolves author names for the filter. */
  documentId: string;
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  hoverAnnotationId?: string | null;
  onSelect: (annotationId: string | null) => void;
  onHover?: (annotationId: string | null) => void;
  /** The drawn-but-not-yet-created anchor; non-null opens the composer. */
  pendingAnchor: Anchor | null;
  creating: boolean;
  onCreate: (comment: string, type?: AnnotationType, priority?: AnnotationPriority) => void;
  onCancelPending: () => void;
  canAnnotate: boolean;
  notify: Notify;
  /** True while an OLDER version is viewed (#306): threads readable, nothing writable. */
  readOnly?: boolean;
  /** True once the review is FINALIZED/CANCELLED (issue #394): no reopening. */
  reviewClosed?: boolean;
  /** The previous visit (issue #307) — null hides every unseen cue. */
  previousSeenAt?: string | null;
}

/** A collapsible, counted group of annotation cards (prototype sidebar section). */
function PanelSection({
  title,
  subtitle,
  icon,
  count,
  newCount = 0,
  defaultOpen = true,
  children,
}: {
  title: string;
  subtitle?: string;
  icon: ReactNode;
  count: number;
  /** Unseen entries in this group (issue #307) — rendered as "· n new". */
  newCount?: number;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const theme = useTheme();
  const [open, setOpen] = useState(defaultOpen);
  return (
    <Box>
      <ButtonBase
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        sx={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          textAlign: 'left',
          borderRadius: 1,
          px: 0.5,
          py: 0.75,
          '&:focus-visible': { boxShadow: theme.qnop.focusRing },
        }}
      >
        <ChevronRight
          size={14}
          aria-hidden
          style={{
            color: theme.palette.text.secondary,
            flexShrink: 0,
            transform: open ? 'rotate(90deg)' : 'none',
            transition: 'transform 150ms ease',
          }}
        />
        <Box
          aria-hidden
          sx={{
            width: 24,
            height: 24,
            borderRadius: 1,
            display: 'grid',
            placeItems: 'center',
            bgcolor: theme.qnop.badge.blue.bg,
            color: theme.qnop.brand.blue,
            flexShrink: 0,
          }}
        >
          {icon}
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="subtitle2" sx={{ lineHeight: 1.25 }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary">
              {subtitle}
            </Typography>
          )}
        </Box>
        <Typography
          variant="caption"
          sx={{
            color: 'text.secondary',
            bgcolor: theme.qnop.surface2,
            borderRadius: 99,
            px: 0.75,
            fontVariantNumeric: 'tabular-nums',
            flexShrink: 0,
          }}
        >
          {count}
        </Typography>
        {newCount > 0 && (
          <Typography
            variant="caption"
            data-testid="section-new-count"
            sx={{ color: theme.qnop.brand.blue, fontWeight: 600, flexShrink: 0 }}
          >
            · {newCount} new
          </Typography>
        )}
      </ButtonBase>
      <Collapse in={open} unmountOnExit>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {children}
        </Stack>
      </Collapse>
    </Box>
  );
}

/**
 * The right-hand review panel: the composer for a pending mark, a status
 * filter, and the annotations of the viewed version grouped into collapsible
 * sections — placed marks ordered by document position, then annotations
 * without a placement on this version (orphaned/failed, ADR-0009). The active
 * annotation expands into its comment thread; hovering a card lights up its
 * mark on the page (and vice versa).
 */
export function AnnotationPanel({
  documentId,
  annotations,
  activeAnnotationId,
  hoverAnnotationId,
  onSelect,
  onHover,
  pendingAnchor,
  creating,
  onCreate,
  onCancelPending,
  canAnnotate,
  notify,
  readOnly = false,
  reviewClosed = false,
  previousSeenAt = null,
}: AnnotationPanelProps) {
  const [filters, setFilters] = useState<AnnotationFilters>(EMPTY_FILTERS);
  const userId = useAuthStore((state) => state.userId);
  const { resolveWith, isPending: resolving } = useResolveWithFeedback(notify);
  const { reopenWith } = useReopenWithFeedback(notify);

  // Sort + status-filter once per (annotations, filter) change, not on every
  // render (e.g. a hover or selection): the list can be large and the sort is
  // O(n log n) (issue #334).
  // Author names for the filter facet, resolved like the tasks board does:
  // self by display name, reviewers via the participant directory, anyone the
  // directory does not know (the owner) as a plain "Participant".
  const participants = useParticipants(documentId).data?.participants ?? [];
  const displayName = useAuthStore((state) => state.displayName);
  const authorNameOf = (authorId: string) =>
    authorId === userId
      ? (displayName ?? 'You')
      : (participants.find((participant) => participant.principalId === authorId)?.displayName ??
        'Participant');
  const authors: FilterAuthor[] = useMemo(
    () =>
      [...new Set(annotations.map((annotation) => annotation.authorId))].map((id) => ({
        id,
        name: authorNameOf(id),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- names derive from the two inputs below
    [annotations, participants, userId, displayName],
  );

  const { placed, unplaced, hiddenByFilter } = useMemo(() => {
    const matches = (annotation: AnnotationView) =>
      matchesFilters(annotation, filters, authorNameOf(annotation.authorId));
    const sorted = [...annotations].sort(compareAnnotationsByPosition);
    const placedItems = sorted.filter((a) => a.anchor && matches(a));
    const unplacedItems = sorted.filter((a) => !a.anchor && matches(a));
    return {
      placed: placedItems,
      unplaced: unplacedItems,
      hiddenByFilter: annotations.length > 0 && placedItems.length + unplacedItems.length === 0,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- authorNameOf derives from authors' inputs
  }, [annotations, filters, participants, userId, displayName]);

  const renderItem = (annotation: AnnotationView) => {
    const active = annotation.id === activeAnnotationId;
    return (
      <Stack key={annotation.id} spacing={0}>
        <AnnotationListItem
          annotation={annotation}
          active={active}
          previousSeenAt={previousSeenAt}
          linked={annotation.id === hoverAnnotationId}
          onSelect={onSelect}
          onHover={onHover}
        />
        <Collapse in={active} unmountOnExit>
          {!readOnly && mayResolveAnnotation(annotation, userId) && (
            <ResolveBar disabled={resolving} onResolve={(note) => resolveWith(annotation, note)} />
          )}
          {/* The thread stays inside the unit's card. */}
          <CommentThread
            annotationId={annotation.id}
            notify={notify}
            readOnly={readOnly}
            closed={annotation.status !== AnnotationStatus.Open}
            onReopen={
              !readOnly && !reviewClosed && mayReopenAnnotation(annotation, userId)
                ? () => reopenWith(annotation)
                : undefined
            }
            previousSeenAt={previousSeenAt}
            skipOpener
          />
        </Collapse>
      </Stack>
    );
  };

  return (
    <SectionCard
      icon={NotebookPen}
      title={`Annotations (${annotations.length})`}
      description="Marks and their discussion on this version."
    >
      <Stack spacing={1.5}>
        {annotations.length > 0 && (
          <PanelFilterBar filters={filters} onChange={setFilters} authors={authors} />
        )}
        {pendingAnchor && (
          <Composer
            pendingAnchor={pendingAnchor}
            creating={creating}
            onCreate={onCreate}
            onCancel={onCancelPending}
          />
        )}
        {annotations.length === 0 && !pendingAnchor && (
          <Typography variant="body2" color="text.secondary">
            No annotations yet.
            {canAnnotate && ' Select text or draw a region on the document to add one.'}
          </Typography>
        )}
        {hiddenByFilter && (
          <Typography variant="body2" color="text.secondary">
            No annotations match this filter.
          </Typography>
        )}
        {placed.length > 0 && (
          <PanelSection
            title="On this version"
            subtitle="Anchored to the document"
            icon={<Link2 size={13} aria-hidden />}
            count={placed.length}
            newCount={placed.filter((a) => isUnseen(a, previousSeenAt, userId)).length}
          >
            {placed.map(renderItem)}
          </PanelSection>
        )}
        {unplaced.length > 0 && (
          <PanelSection
            title="Not placed on this version"
            subtitle="Anchor lost — needs manual handling"
            icon={<Unlink size={13} aria-hidden />}
            count={unplaced.length}
            newCount={unplaced.filter((a) => isUnseen(a, previousSeenAt, userId)).length}
          >
            {unplaced.map(renderItem)}
          </PanelSection>
        )}
      </Stack>
    </SectionCard>
  );
}
