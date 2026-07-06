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
import { useAuthStore } from '../../../stores/authStore';
import { shortRelativeTime } from '../../../utils/relativeTime';
import { UserAvatar } from '../../shell/UserAvatar';
import { isDocumentScoped } from '../annotationScope';
import { AnnotationBadgeRow } from './AnnotationBadgeRow';

const DATE_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
});

interface AnnotationHeadProps {
  annotation: AnnotationView;
  /** Adds the "New" badge (issue #307). */
  unseen?: boolean;
}

/**
 * The opening annotation as the discussion's root post (issue #403): badge
 * row, the anchored passage styled as a real quotation, the opening text and
 * the author line. ONE component shared by the panel's expanded card and the
 * focus mode's floating card, so the head reads identically on both.
 */
export function AnnotationHead({ annotation, unseen = false }: AnnotationHeadProps) {
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
    <Stack spacing={1} data-testid="annotation-head-card">
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
        <Typography
          variant="body2"
          sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
          data-testid="opening-text"
        >
          {openerText}
        </Typography>
      )}
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <UserAvatar name={authorName} size={20} imageUrl={own ? avatarUrl : null} />
        <Typography
          variant="caption"
          color="text.secondary"
          noWrap
          title={DATE_FORMAT.format(new Date(annotation.createdAt))}
        >
          {authorName} · {shortRelativeTime(annotation.createdAt)}
        </Typography>
      </Stack>
    </Stack>
  );
}
