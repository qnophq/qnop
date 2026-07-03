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
import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import ButtonBase from '@mui/material/ButtonBase';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ChevronRight, Link2, NotebookPen, Unlink } from 'lucide-react';
import type { Anchor, AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import type { Notify } from '../../admin/layout/useToast';
import { SectionCard } from '../../admin/layout/SectionCard';
import { compareAnnotationsByPosition } from '../viewer/anchoring';
import { AnnotationListItem } from './AnnotationListItem';
import { CommentThread } from './CommentThread';

type StatusFilter = 'all' | 'open' | 'decided';

const FILTERS: { value: StatusFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'decided', label: 'Decided' },
];

interface AnnotationPanelProps {
  annotations: AnnotationView[];
  activeAnnotationId: string | null;
  hoverAnnotationId?: string | null;
  onSelect: (annotationId: string | null) => void;
  onHover?: (annotationId: string | null) => void;
  /** The drawn-but-not-yet-created anchor; non-null opens the composer. */
  pendingAnchor: Anchor | null;
  creating: boolean;
  onCreate: (comment: string) => void;
  onCancelPending: () => void;
  canAnnotate: boolean;
  notify: Notify;
}

/** The composer for a freshly drawn anchor: optional first comment, then create. */
function Composer({
  pendingAnchor,
  creating,
  onCreate,
  onCancel,
}: {
  pendingAnchor: Anchor;
  creating: boolean;
  onCreate: (comment: string) => void;
  onCancel: () => void;
}) {
  const [comment, setComment] = useState('');
  const quote = pendingAnchor.textQuote?.quote;
  return (
    <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="annotation-composer">
      <Stack spacing={1}>
        <Typography variant="subtitle2">New annotation</Typography>
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{
            fontStyle: quote ? 'italic' : 'normal',
            display: '-webkit-box',
            WebkitLineClamp: 3,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {quote ? `“${quote}”` : `Region on page ${pendingAnchor.region.surfaceIndex + 1}`}
        </Typography>
        <TextField
          multiline
          minRows={2}
          size="small"
          placeholder="Add a comment (optional)"
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          slotProps={{ htmlInput: { maxLength: 20000, 'aria-label': 'Annotation comment' } }}
        />
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button size="small" onClick={onCancel} disabled={creating}>
            Cancel
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={() => onCreate(comment)}
            disabled={creating}
          >
            Create annotation
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}

/** A collapsible, counted group of annotation cards (prototype sidebar section). */
function PanelSection({
  title,
  subtitle,
  icon,
  count,
  defaultOpen = true,
  children,
}: {
  title: string;
  subtitle?: string;
  icon: ReactNode;
  count: number;
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
}: AnnotationPanelProps) {
  const [filter, setFilter] = useState<StatusFilter>('all');

  const matchesFilter = (annotation: AnnotationView) =>
    filter === 'all' ||
    (filter === 'open'
      ? annotation.status === AnnotationStatus.Open
      : annotation.status !== AnnotationStatus.Open);

  const sorted = [...annotations].sort(compareAnnotationsByPosition);
  const placed = sorted.filter((annotation) => annotation.anchor && matchesFilter(annotation));
  const unplaced = sorted.filter((annotation) => !annotation.anchor && matchesFilter(annotation));
  const hiddenByFilter = annotations.length > 0 && placed.length + unplaced.length === 0;

  const renderItem = (annotation: AnnotationView) => {
    const active = annotation.id === activeAnnotationId;
    return (
      <Stack key={annotation.id} spacing={0}>
        <AnnotationListItem
          annotation={annotation}
          active={active}
          linked={annotation.id === hoverAnnotationId}
          onClick={() => onSelect(active ? null : annotation.id)}
          onHover={onHover}
        />
        <Collapse in={active} unmountOnExit>
          <CommentThread annotationId={annotation.id} notify={notify} />
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
          <Stack direction="row" spacing={0.5} role="group" aria-label="Filter annotations">
            {FILTERS.map(({ value, label }) => (
              <Chip
                key={value}
                label={label}
                size="small"
                variant={filter === value ? 'filled' : 'outlined'}
                color={filter === value ? 'primary' : 'default'}
                onClick={() => setFilter(value)}
                aria-pressed={filter === value}
              />
            ))}
          </Stack>
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
          >
            {unplaced.map(renderItem)}
          </PanelSection>
        )}
      </Stack>
    </SectionCard>
  );
}
