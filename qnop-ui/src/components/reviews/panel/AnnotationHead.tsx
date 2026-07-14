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
import { useDocument } from '../../../api/hooks/useDocuments';
import type { Notify } from '../../admin/layout/useToast';
import { realAuthorId } from '../../people/realAuthorId';
import { UserHoverCard } from '../../people/UserHoverCard';
import { useReviewDocumentId } from '../reviewDocumentId';
import { CopyTextButton } from '../CopyTextButton';
import { CopyLinkButton } from '../permalink/CopyLinkButton';
import { AddReactionButton } from '../reactions/AddReactionButton';
import { ReactionBar } from '../reactions/ReactionBar';
import { useToggleAnnotationReaction } from '../reactions/useReactions';
import { useAuthStore } from '../../../stores/authStore';
import { shortRelativeTime } from '../../../utils/relativeTime';
import { UserAvatar } from '../../shell/UserAvatar';
import { avatarSrc } from '../../../utils/avatarUrl';
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
  /** Confirms a reviewed MOVED placement (issue #326) — rendered beside the Moved chip. */
  onConfirmPlacement?: () => void;
  /** Arms re-attaching a lost placement (issue #457) — rendered beside the Orphaned chip. */
  onReattachPlacement?: () => void;
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
  onConfirmPlacement,
  onReattachPlacement,
}: AnnotationHeadProps) {
  const theme = useTheme();
  // Reactions on the opener (issue #410) — active wherever notify travels;
  // a closed review answers REVIEW_CLOSED and the optimistic flip rolls back.
  const toggleReaction = useToggleAnnotationReaction(annotation.id, notify ?? (() => undefined));
  const onToggleReaction = notify
    ? (emoji: string, reacted: boolean) => toggleReaction.mutate({ emoji, reacted })
    : undefined;
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  // Full opener once the thread is cached; the server-side excerpt until then.
  const cachedComments = useComments(annotation.id, false).data?.comments ?? [];
  const openerText = cachedComments[0]?.body ?? annotation.firstComment ?? null;
  // Every annotation offers a copy payload (issue #478): the quoted passage
  // when anchored to text, otherwise the opening text (document-scoped and
  // region annotations carry no quote).
  const copyText = annotation.anchor?.textQuote?.quote ?? openerText;
  const own = annotation.authorId === userId;
  // The author name is resolved server-side, honouring per-review anonymity
  // (issue #413): the real name in a normal review, a stable "Participant N"
  // pseudonym for foreign authors in an anonymous one. Own contributions read
  // "You" from the auth store.
  const authorName = own ? (displayName ?? 'You') : (annotation.authorDisplayName ?? 'Participant');
  // The hover card (issue #482) attaches only to guaranteed-real author ids —
  // in an anonymous review a foreign authorId is a pseudonym token.
  const review = useDocument(useReviewDocumentId()).data;
  const hoverUserId = realAuthorId(review, userId, annotation.authorId);
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
        <UserHoverCard userId={hoverUserId} profileName={authorName}>
          <UserAvatar
            name={authorName}
            size={AUTHOR_AVATAR_SIZE}
            imageUrl={own ? avatarUrl : avatarSrc(annotation.authorId)}
          />
        </UserHoverCard>
        <Box sx={{ minWidth: 0 }}>
          <UserHoverCard userId={hoverUserId}>
            <Typography
              noWrap
              sx={{ fontWeight: 700, fontSize: '0.9375rem', lineHeight: 1.3 }}
              data-testid="annotation-author"
            >
              {authorName}
            </Typography>
          </UserHoverCard>
          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0 }}>
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
            {(onToggleReaction || (copyText && notify) || (permalinkUrl && notify)) && (
              // Directly after the timestamp, exactly like a comment row's
              // link. The negative margin keeps the icon buttons from growing
              // the caption line beyond the avatar's height.
              <Box
                className="annotation-hover-actions"
                sx={{ display: 'flex', alignItems: 'center', flexShrink: 0, my: '-3px' }}
              >
                {copyText && notify && (
                  <CopyTextButton
                    text={copyText}
                    notify={notify}
                    label={quote ? 'Copy quote' : 'Copy text'}
                    copiedMessage={quote ? 'Quote copied.' : 'Text copied.'}
                  />
                )}
                {permalinkUrl && notify && (
                  <CopyLinkButton
                    url={permalinkUrl}
                    notify={notify}
                    label="Copy link to annotation"
                  />
                )}
                {onToggleReaction && (
                  <AddReactionButton
                    onPick={(emoji) =>
                      onToggleReaction(
                        emoji,
                        annotation.reactions.find((group) => group.emoji === emoji)?.reactedByMe ??
                          false,
                      )
                    }
                  />
                )}
              </Box>
            )}
          </Stack>
        </Box>
      </Stack>
      <AnnotationBadgeRow
        annotation={annotation}
        unseen={unseen}
        onConfirmPlacement={onConfirmPlacement}
        onReattachPlacement={onReattachPlacement}
      />
      {/* The anchored passage, styled as a real quotation. */}
      {quote ? (
        <Box
          sx={{
            borderLeft: '3px solid',
            borderColor: 'divider',
            bgcolor: theme.qnop.surface2,
            borderRadius: '0 6px 6px 0',
            // The passage is copy material (issue #478) — selectable even
            // inside the clickable card (ButtonBase disables selection).
            userSelect: 'text',
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
      {/* The opener's reaction chips (issue #410), Slack-style under the text. */}
      {onToggleReaction && annotation.reactions.length > 0 && (
        <ReactionBar reactions={annotation.reactions} onToggle={onToggleReaction} />
      )}
    </Stack>
  );
}
