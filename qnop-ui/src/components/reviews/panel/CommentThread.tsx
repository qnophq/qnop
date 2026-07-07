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

import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import InputBase from '@mui/material/InputBase';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { CircleCheck, Lock, RotateCcw, SendHorizontal } from 'lucide-react';
import { useAddComment, useComments } from '../../../api/hooks/useComments';
import { apiErrorCode } from '../../../utils/apiError';
import { isSubmitShortcut, submitShortcutLabel } from '../../../utils/platform';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { isNewComment } from '../newSince';
import type { BuildPermalink } from '../useReviewPermalink';
import { MarkdownHint } from '../markdown/MarkdownHint';
import { CommentBubble } from './CommentBubble';
import { UserAvatar } from '../../shell/UserAvatar';

const AVATAR_SIZE = 26;
/** Centre of the avatar column — where the timeline rail runs. */
const LINE_LEFT = AVATAR_SIZE / 2 - 1;

interface CommentThreadProps {
  annotationId: string;
  notify: Notify;
  /** Hides the reply composer — older versions are a read-only record (#306). */
  readOnly?: boolean;
  /**
   * READ_ONLY thread policy (issue #413): the thread is visible but the viewer is neither its
   * author nor the owner, so the composer gives way to a quiet "only author and owner" line.
   */
  policyReadOnly?: boolean;
  /** True on a RESOLVED annotation (#403): the thread is a closed record. */
  closed?: boolean;
  /** Reopens the annotation (issue #394) — set only when the viewer may. */
  onReopen?: () => void;
  /** The previous visit (issue #307) — enables the "new" divider inside the thread. */
  previousSeenAt?: string | null;
  /** True when the surrounding card already renders the opening annotation (issue #403). */
  skipOpener?: boolean;
  /** Builds a per-comment permalink (issue #412) — enables the meta-line copy affordance. */
  buildPermalink?: BuildPermalink;
  /**
   * A comment permalink target (issue #412): once the thread has loaded, its bubble is scrolled
   * into view and pulsed once. An unknown id degrades to a toast. Consumed via `onScrolledToComment`.
   */
  scrollToCommentId?: string | null;
  onScrolledToComment?: () => void;
}

/**
 * The annotation's discussion — the comment anatomy of Facebook/Instagram
 * married to the timeline of the original design (issue #403): a vertical
 * rail runs through the avatar column and connects the root card to every
 * reply and the composer; each comment is a softly rounded bubble carrying
 * the bold author name above the text, with the compact relative timestamp
 * in a meta line UNDER the bubble (full date in its tooltip; likes join
 * that line with #410). Author names are resolved server-side and travel on
 * each comment (issue #413), honouring per-review anonymity; own contributions
 * read "You".
 */
export function CommentThread({
  annotationId,
  notify,
  readOnly = false,
  policyReadOnly = false,
  closed = false,
  onReopen,
  previousSeenAt = null,
  skipOpener = false,
  buildPermalink,
  scrollToCommentId = null,
  onScrolledToComment,
}: CommentThreadProps) {
  const theme = useTheme();
  const userId = useAuthStore((state) => state.userId);
  const displayName = useAuthStore((state) => state.displayName);
  const avatarUrl = useAuthStore((state) => state.avatarUrl);
  const commentsQuery = useComments(annotationId);
  const addComment = useAddComment(annotationId);
  const [draft, setDraft] = useState('');
  const pulseColor = alpha(theme.qnop.brand.blue, 0.5);

  const submit = () => {
    const body = draft.trim();
    if (!body) return;
    addComment.mutate(body, {
      onSuccess: () => setDraft(''),
      onError: (error) => {
        const code = apiErrorCode(error);
        notify(
          code === 'ANNOTATION_ALREADY_RESOLVED'
            ? 'The annotation was resolved — its thread is closed.'
            : code === 'THREAD_READ_ONLY'
              ? 'Only the author and the owner can reply in this review.'
              : 'Could not add the comment.',
          'error',
        );
      },
    });
  };

  const comments = useMemo(() => commentsQuery.data?.comments ?? [], [commentsQuery.data]);
  // With the opening annotation living in the head card (issue #403), the
  // timeline carries only the replies.
  const visibleComments = skipOpener ? comments.slice(1) : comments;
  const firstNewCommentId = visibleComments.find((comment) =>
    isNewComment(comment, previousSeenAt, userId),
  )?.id;

  // A comment permalink target (issue #412): once the thread has loaded, scroll
  // its bubble into view (reduced-motion aware) and pulse it once. The opening
  // comment already shows in the head card, so it needs no scroll; an unknown
  // id degrades to a toast. Handled once per target so a re-render never
  // re-scrolls or re-toasts.
  const handledScrollRef = useRef<string | null>(null);
  useEffect(() => {
    if (!scrollToCommentId || commentsQuery.isPending) return;
    if (handledScrollRef.current === scrollToCommentId) return;
    handledScrollRef.current = scrollToCommentId;

    const index = comments.findIndex((comment) => comment.id === scrollToCommentId);
    if (index === -1) {
      notify('This comment no longer exists.', 'error');
    } else if (!(skipOpener && index === 0)) {
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      const el = document.getElementById(`comment-${scrollToCommentId}`);
      el?.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'center' });
      // A one-shot ring in the unseen-marker blue, driven imperatively so it
      // outlives the parent clearing the target; skipped under reduced motion
      // and where the Web Animations API is unavailable (jsdom).
      if (!reduced) {
        el?.animate?.(
          [
            { boxShadow: `0 0 0 3px ${pulseColor}` },
            { boxShadow: '0 0 0 3px transparent', offset: 0.6 },
            { boxShadow: '0 0 0 0 transparent' },
          ],
          { duration: 1200, easing: 'ease-out' },
        );
      }
    }
    onScrolledToComment?.();
  }, [
    scrollToCommentId,
    commentsQuery.isPending,
    comments,
    skipOpener,
    notify,
    onScrolledToComment,
    pulseColor,
  ]);

  return (
    <Box sx={{ position: 'relative', mt: 0.5, ml: 1.5, pl: 0 }}>
      {/* The timeline rail connecting the annotation card to its thread. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          left: LINE_LEFT,
          top: -6,
          bottom: 18,
          width: 2,
          borderRadius: 1,
          bgcolor: theme.palette.divider,
        }}
      />
      <Stack spacing={1} sx={{ pt: 1 }}>
        {commentsQuery.isPending && (
          <Stack sx={{ alignItems: 'center', py: 1 }}>
            <CircularProgress size={18} aria-label="Loading comments" />
          </Stack>
        )}
        {commentsQuery.isError && (
          <Typography variant="body2" color="error" sx={{ pl: 4.5 }}>
            Could not load the comments.
          </Typography>
        )}
        {visibleComments.map((comment) => {
          const own = comment.authorId === userId;
          // Server-resolved name, honouring per-review anonymity (issue #413).
          const name = own ? (displayName ?? 'You') : (comment.authorDisplayName ?? 'Participant');
          return (
            <Fragment key={comment.id}>
              {comment.id === firstNewCommentId && (
                <Stack
                  direction="row"
                  spacing={1}
                  data-testid="new-since-divider"
                  sx={{ alignItems: 'center', pl: 4.5 }}
                >
                  <Box
                    sx={{ flex: 1, height: '1px', bgcolor: alpha(theme.qnop.brand.blue, 0.4) }}
                  />
                  <Typography
                    variant="caption"
                    sx={{ color: theme.qnop.brand.blue, fontWeight: 600, whiteSpace: 'nowrap' }}
                  >
                    New since your last visit
                  </Typography>
                  <Box
                    sx={{ flex: 1, height: '1px', bgcolor: alpha(theme.qnop.brand.blue, 0.4) }}
                  />
                </Stack>
              )}
              <CommentBubble
                name={name}
                own={own}
                avatarUrl={avatarUrl}
                body={comment.body}
                createdAt={comment.createdAt}
                domId={`comment-${comment.id}`}
                permalinkUrl={buildPermalink?.(annotationId, comment.id)}
                notify={notify}
              />
            </Fragment>
          );
        })}
        {!commentsQuery.isPending && !commentsQuery.isError && visibleComments.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ pl: 4.5 }}>
            {skipOpener
              ? closed
                ? 'No replies.'
                : 'No replies yet.'
              : 'No comments yet. Start the discussion.'}
          </Typography>
        )}
        {/* A resolved annotation's thread is a record (#403): the composer
            gives way to a quiet closing line, so its absence reads as a state,
            not a glitch. */}
        {closed && !readOnly && (
          <Stack
            direction="row"
            spacing={0.75}
            data-testid="thread-closed-note"
            sx={{ alignItems: 'center', pl: 4.5, color: 'text.secondary', minHeight: 26 }}
          >
            <CircleCheck size={13} aria-hidden color={theme.palette.success.main} />
            <Typography variant="caption">Resolved — this thread is closed.</Typography>
            {onReopen && (
              <Button
                size="small"
                variant="text"
                startIcon={<RotateCcw size={12} />}
                onClick={onReopen}
                sx={{ ml: 0.5, py: 0, minHeight: 0, fontSize: 12 }}
              >
                Reopen
              </Button>
            )}
          </Stack>
        )}
        {/* READ_ONLY policy (issue #413): the thread is a record for this
            viewer — a quiet line replaces the composer, so its absence reads as
            a state, not a glitch. */}
        {policyReadOnly && !closed && !readOnly && (
          <Stack
            direction="row"
            spacing={0.75}
            data-testid="thread-policy-readonly-note"
            sx={{ alignItems: 'center', pl: 4.5, color: 'text.secondary', minHeight: 26 }}
          >
            <Lock size={13} aria-hidden />
            <Typography variant="caption">
              Only the author and the owner can reply in this review.
            </Typography>
          </Stack>
        )}
        {/* Composer continues the thread rail with the signed-in user's avatar.
            Hidden on read-only (older) versions — the same thread stays
            writable when the annotation is opened on the latest version. */}
        {!readOnly && !closed && !policyReadOnly && (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
            <Box sx={{ position: 'relative', zIndex: 1, pt: 0.5 }}>
              <UserAvatar name={displayName ?? 'You'} size={AVATAR_SIZE} imageUrl={avatarUrl} />
            </Box>
            {/* The borderless composer block of the social pattern (#403):
                a soft rounded surface holding the multiline field, with the
                action row underneath — send bottom-right, like the feeds. */}
            <Box
              sx={{
                flex: 1,
                minWidth: 0,
                bgcolor: theme.qnop.surface2,
                borderRadius: '12px',
                px: 1.5,
                pt: 1,
                pb: 0.5,
                transition: 'box-shadow 120ms ease',
                '&:focus-within': {
                  boxShadow: `0 0 0 2px ${alpha(theme.qnop.brand.blue, 0.25)}`,
                },
                '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
              }}
            >
              <InputBase
                multiline
                minRows={2}
                fullWidth
                placeholder="Add a comment"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (isSubmitShortcut(event)) {
                    event.preventDefault();
                    submit();
                  }
                }}
                inputProps={{ maxLength: 20000, 'aria-label': 'Add a comment' }}
                sx={{ p: 0, fontSize: 14, lineHeight: 1.45 }}
              />
              <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                <MarkdownHint />
                <Tooltip title={`Send (${submitShortcutLabel()})`}>
                  <span>
                    <IconButton
                      size="small"
                      aria-label="Comment"
                      color="primary"
                      onClick={submit}
                      disabled={!draft.trim() || addComment.isPending}
                    >
                      <SendHorizontal size={16} />
                    </IconButton>
                  </span>
                </Tooltip>
              </Stack>
            </Box>
          </Stack>
        )}
      </Stack>
    </Box>
  );
}
