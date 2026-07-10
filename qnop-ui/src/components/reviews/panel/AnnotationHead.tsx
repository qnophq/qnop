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

import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import type { AnnotationView } from '../../../api/generated';
import { useComments } from '../../../api/hooks/useComments';
import type { Notify } from '../../admin/layout/useToast';
import { CopyLinkButton } from '../permalink/CopyLinkButton';
import { useAuthStore } from '../../../stores/authStore';
import { shortRelativeTime } from '../../../utils/relativeTime';
import { UserAvatar } from '../../shell/UserAvatar';
import { isDocumentScoped } from '../annotationScope';
import { Markdown } from '../markdown/Markdown';
import { AnnotationBadgeRow } from './AnnotationBadgeRow';

const DATE_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

/** Larger than a reply's avatar (28) — the opener carries the thread. */
const AUTHOR_AVATAR_SIZE = 36;

interface AnnotationHeadProps {
  annotation: AnnotationView;
  /** Adds the "New" badge (issue #307). */
  unseen?: boolean;
  /** With `notify`, the author row carries the hover-revealed copy-link (issue #412). */
  permalinkUrl?: string;
  notify?: Notify;
}

/**
 * The opening annotation as the discussion's root post (issue #403): the
 * author header leading the card (issue #445 follow-up) — a larger avatar with
 * the bold name over a "Started this discussion" line, so whoever opened the
 * thread is unmistakable — then the badge row, the anchored passage styled as
 * a real quotation, and the opening text. ONE component shared by the panel's
 * expanded card and the focus mode's floating card, so the head reads
 * identically on both.
 */
export function AnnotationHead({
  annotation,
  unseen = false,
  permalinkUrl,
  notify,
}: AnnotationHeadProps) {
  const theme = useTheme();
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  // Full opener once the thread is cached; the server-side excerpt until then.
  const cachedComments = useComments(annotation.id, false).data?.comments ?? [];
  const openerText = cachedComments[0]?.body ?? annotation.firstComment ?? null;
  const own = annotation.authorId === userId;
  // The author name is resolved server-side, honouring per-review anonymity
  // (issue #413): the real name in a normal review, a stable "Participant N"
  // pseudonym for foreign authors in an anonymous one. Own contributions read
  // "You" from the auth store.
  const authorName = own ? (displayName ?? 'You') : (annotation.authorDisplayName ?? 'Participant');
  const quote = annotation.anchor?.textQuote?.quote;
  const fallbackLabel = annotation.anchor?.region
    ? 'Region annotation'
    : 'No placement on this version';

  return (
    <Stack
      spacing={1.25}
      data-testid="annotation-head-card"
      sx={{
        // The same quiet reveal the comment rows use (issue #412): the
        // copy-link stays invisible until the card is hovered or focused;
        // pointer-less devices see it permanently.
        '& .annotation-hover-actions': {
          opacity: 0,
          transition: 'opacity 120ms ease',
          '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
          '@media (hover: none)': { opacity: 1 },
        },
        '&:hover .annotation-hover-actions, &:focus-within .annotation-hover-actions': {
          opacity: 1,
        },
      }}
    >
      {/* The thread starter, front and centre — this is their discussion. */}
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', minWidth: 0 }}>
        <UserAvatar name={authorName} size={AUTHOR_AVATAR_SIZE} imageUrl={own ? avatarUrl : null} />
        <Box sx={{ minWidth: 0 }}>
          <Typography
            noWrap
            sx={{ fontWeight: 700, fontSize: '0.9375rem', lineHeight: 1.3 }}
            data-testid="annotation-author"
          >
            {authorName}
          </Typography>
          <Typography
            variant="caption"
            color="text.secondary"
            noWrap
            component="p"
            title={DATE_FORMAT.format(new Date(annotation.createdAt))}
            sx={{ lineHeight: 1.4 }}
          >
            Started this discussion · {shortRelativeTime(annotation.createdAt)}
          </Typography>
        </Box>
        {permalinkUrl && notify && (
          // Inline after the author block, exactly like a comment row's link
          // after its timestamp (no ml:auto — Stack spacing margins would
          // override it anyway).
          <Box className="annotation-hover-actions" sx={{ display: 'flex', flexShrink: 0 }}>
            <CopyLinkButton url={permalinkUrl} notify={notify} label="Copy link to annotation" />
          </Box>
        )}
      </Stack>
      <AnnotationBadgeRow annotation={annotation} unseen={unseen} />
      {/* The anchored passage, styled as a real quotation. */}
      {quote ? (
        <Box
          sx={{
            borderLeft: '3px solid',
            borderColor: 'divider',
            bgcolor: theme.qnop.surface2,
            borderRadius: '0 6px 6px 0',
            px: 1.25,
            py: 0.75,
          }}
        >
          <Typography
            variant="body2"
            sx={{
              fontStyle: 'italic',
              color: 'text.secondary',
              display: '-webkit-box',
              WebkitLineClamp: 4,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            “{quote}”
          </Typography>
        </Box>
      ) : (
        // A document-scoped annotation (issue #395) has no passage; its scope reads from the
        // "Whole document" chip in the badge row above, so no fallback line is needed here.
        !isDocumentScoped(annotation) && (
          <Typography variant="body2" color="text.secondary">
            {fallbackLabel}
          </Typography>
        )
      )}
      {openerText && (
        // The opening comment renders as sanitised Markdown (issue #427).
        <Box data-testid="opening-text">
          <Markdown>{openerText}</Markdown>
        </Box>
      )}
    </Stack>
  );
}
