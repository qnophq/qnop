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
import Button from '@mui/material/Button';
import ButtonBase from '@mui/material/ButtonBase';
import Collapse from '@mui/material/Collapse';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ChevronRight, FileText, Link2, NotebookPen, Plus } from 'lucide-react';
import type {
  AnnotationPriority,
  AnnotationType,
  Anchor,
  AnnotationView,
} from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { SectionCard } from '../../admin/layout/SectionCard';
import { compareAnnotationsByPosition } from '../viewer/anchoring';
import { isUnseen } from '../newSince';
import { isDocumentScoped } from '../annotationScope';
import type { BuildPermalink } from '../useReviewPermalink';
import { useConfirmPlacement } from '../../../api/hooks/useAnnotations';
import { AnnotationListItem } from './AnnotationListItem';
import type { UploadedAttachment } from '../markdown/useCommentAttachmentUpload';
import { CommentThread } from './CommentThread';
import { Composer } from './Composer';
import type { FilterAuthor } from './PanelFilterBar';
import { PanelFilterBar } from './PanelFilterBar';
import { ReanchorBanner } from './ReanchorBanner';
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
  /** True for an anonymous review (issue #413) — hides the author filter facet. */
  anonymous?: boolean;
  /** Thread participation policy (issue #413) — READ_ONLY suppresses foreign composers. */
  threadParticipation?: string;
  /** The review owner (issue #413) — the owner may always comment under any policy. */
  ownerId?: string;
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
  /** Uploads a local composer file (issue #446); built by the page, which owns the document id. */
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
  /** True while an OLDER version is viewed (#306): threads readable, nothing writable. */
  readOnly?: boolean;
  /** The viewed version — the scope of placement outcomes and their confirmation (issue #326). */
  versionNumber?: number | null;
  /**
   * Arms re-attaching a lost placement (issue #457) — the page owns the
   * viewer, so it turns the next selection into the new anchor.
   */
  onArmReattach?: (annotation: AnnotationView) => void;
  /** True once the review is FINALIZED/CANCELLED (issue #394): no reopening. */
  reviewClosed?: boolean;
  /** Drops the section's outer card frame — the focus drawer brings its own edge. */
  frameless?: boolean;
  /** The previous visit (issue #307) — null hides every unseen cue. */
  previousSeenAt?: string | null;
  /** Builds annotation/comment permalinks (issue #412) — enables the copy affordances. */
  buildPermalink?: BuildPermalink;
  /** A comment permalink target (issue #412) — scrolled to + pulsed in the active thread. */
  scrollToCommentId?: string | null;
  onScrolledToComment?: () => void;
  /**
   * Opens the "new whole-document task" dialog (issue #395) — a general remark that needs no
   * selection. When set (and the review is writable) a quiet "Global annotation" button rides the
   * panel header, so document-scoped annotations can also be raised from the document and focus
   * views.
   */
  onNewDocumentNote?: () => void;
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
  anonymous = false,
  threadParticipation = 'OPEN',
  ownerId,
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
  onUploadAttachment,
  readOnly = false,
  versionNumber = null,
  onArmReattach,
  reviewClosed = false,
  frameless = false,
  previousSeenAt = null,
  buildPermalink,
  scrollToCommentId = null,
  onScrolledToComment,
  onNewDocumentNote,
}: AnnotationPanelProps) {
  const [filters, setFilters] = useState<AnnotationFilters>(EMPTY_FILTERS);
  const userId = useAuthStore((state) => state.userId);
  const { resolveWith, isPending: resolving } = useResolveWithFeedback(notify);
  const confirmPlacement = useConfirmPlacement(notify);
  const { reopenWith } = useReopenWithFeedback(notify);

  // Author names are resolved server-side and travel on the annotation itself
  // (issue #413), honouring per-review anonymity — the real name in a normal
  // review, a stable "Participant N" pseudonym in an anonymous one. Own
  // contributions read "You" from the auth store.
  const displayName = useAuthStore((state) => state.displayName);
  const authorNameOf = (annotation: AnnotationView) =>
    annotation.authorId === userId
      ? (displayName ?? 'You')
      : (annotation.authorDisplayName ?? 'Participant');
  // The author facet is meaningless (and would leak nothing useful) in an
  // anonymous review, so it is dropped entirely there.
  const authors: FilterAuthor[] = useMemo(() => {
    if (anonymous) return [];
    const byId = new Map<string, string>();
    for (const annotation of annotations) {
      if (!byId.has(annotation.authorId)) byId.set(annotation.authorId, authorNameOf(annotation));
    }
    return [...byId].map(([id, name]) => ({ id, name }));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- names derive from the inputs below
  }, [annotations, anonymous, userId, displayName]);

  // Sort + status-filter once per (annotations, filter) change, not on every
  // render (e.g. a hover or selection): the list can be large and the sort is
  // O(n log n) (issue #334).
  const { located, documentScoped, hiddenByFilter } = useMemo(() => {
    const matches = (annotation: AnnotationView) =>
      matchesFilters(annotation, filters, authorNameOf(annotation));
    const sorted = [...annotations].sort(compareAnnotationsByPosition);
    // Located annotations anchor to a passage; document-scoped ones (issue #395) apply to the
    // whole document — no anchor, never orphaned — and group on their own.
    const locatedItems = sorted.filter((a) => !isDocumentScoped(a) && matches(a));
    const documentScopedItems = sorted.filter((a) => isDocumentScoped(a) && matches(a));
    return {
      located: locatedItems,
      documentScoped: documentScopedItems,
      hiddenByFilter:
        annotations.length > 0 && locatedItems.length + documentScopedItems.length === 0,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- authorNameOf derives from the inputs below
  }, [annotations, filters, userId, displayName]);

  // Under a non-OPEN policy (issue #413) only the annotation's author and the
  // owner may reply; the composer is suppressed for everyone else. (A PRIVATE
  // foreign thread is not in the list at all, so this only bites READ_ONLY.)
  const mayComment = (annotation: AnnotationView) =>
    threadParticipation === 'OPEN' ||
    annotation.authorId === userId ||
    (ownerId != null && userId === ownerId);

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
          permalinkUrl={buildPermalink?.(annotation.id)}
          notify={notify}
          onConfirmPlacement={
            versionNumber != null &&
            !readOnly &&
            !reviewClosed &&
            annotation.placementStatus === 'MOVED' &&
            (userId === ownerId || userId === annotation.authorId)
              ? () => confirmPlacement.mutate({ annotationId: annotation.id, versionNumber })
              : undefined
          }
          onReattachPlacement={
            onArmReattach != null &&
            versionNumber != null &&
            !readOnly &&
            !reviewClosed &&
            (annotation.placementStatus === 'ORPHANED' ||
              annotation.placementStatus === 'FAILED') &&
            (userId === ownerId || userId === annotation.authorId)
              ? () => onArmReattach(annotation)
              : undefined
          }
        />
        <Collapse in={active} unmountOnExit>
          {!readOnly && mayResolveAnnotation(annotation, userId) && (
            <ResolveBar disabled={resolving} onResolve={(note) => resolveWith(annotation, note)} />
          )}
          {/* The thread stays inside the unit's card. */}
          <CommentThread
            annotationId={annotation.id}
            documentId={annotation.documentId}
            notify={notify}
            readOnly={readOnly}
            policyReadOnly={!mayComment(annotation)}
            closed={annotation.status !== AnnotationStatus.Open}
            onReopen={
              !readOnly && !reviewClosed && mayReopenAnnotation(annotation, userId)
                ? () => reopenWith(annotation)
                : undefined
            }
            previousSeenAt={previousSeenAt}
            skipOpener
            buildPermalink={buildPermalink}
            scrollToCommentId={active ? scrollToCommentId : null}
            onScrolledToComment={onScrolledToComment}
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
      frameless={frameless}
      action={
        // Raise a whole-document task without a selection (issue #395) — a quiet peer to the
        // text-selection gesture, offered while the latest version is open for review.
        onNewDocumentNote && !readOnly && !reviewClosed ? (
          <Button
            size="small"
            variant="outlined"
            startIcon={<Plus size={15} />}
            onClick={onNewDocumentNote}
          >
            Global annotation
          </Button>
        ) : undefined
      }
    >
      <Stack spacing={1.5}>
        {versionNumber != null && (
          <ReanchorBanner
            annotations={annotations}
            versionNumber={versionNumber}
            onReview={() => setFilters((current) => ({ ...current, placement: 'attention' }))}
          />
        )}
        {annotations.length > 0 && (
          <PanelFilterBar
            filters={filters}
            onChange={setFilters}
            authors={authors}
            authorFacet={!anonymous}
          />
        )}
        {pendingAnchor && (
          <Composer
            pendingAnchor={pendingAnchor}
            creating={creating}
            onCreate={onCreate}
            onCancel={onCancelPending}
            onUploadAttachment={onUploadAttachment}
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
        {documentScoped.length > 0 && (
          <PanelSection
            title="Whole document"
            subtitle="General remarks — not pinned to a passage"
            icon={<FileText size={13} aria-hidden />}
            count={documentScoped.length}
            newCount={documentScoped.filter((a) => isUnseen(a, previousSeenAt, userId)).length}
          >
            {documentScoped.map(renderItem)}
          </PanelSection>
        )}
        {located.length > 0 && (
          <PanelSection
            title="Anchored to the document"
            subtitle="Placed on this version"
            icon={<Link2 size={13} aria-hidden />}
            count={located.length}
            newCount={located.filter((a) => isUnseen(a, previousSeenAt, userId)).length}
          >
            {located.map(renderItem)}
          </PanelSection>
        )}
      </Stack>
    </SectionCard>
  );
}
