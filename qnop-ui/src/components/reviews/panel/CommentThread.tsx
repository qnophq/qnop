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
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { CircleCheck, CircleSlash, Lock, RotateCcw, SendHorizontal } from 'lucide-react';
import { useAddComment, useComments } from '../../../api/hooks/useComments';
import { useDocument } from '../../../api/hooks/useDocuments';
import { useParticipants } from '../../../api/hooks/useReviews';
import { ParticipantKind } from '../../../api/generated';
import type { MentionCandidate } from '../markdown/mentionToken';
import { realAuthorId } from '../../people/realAuthorId';
import { apiErrorCode } from '../../../utils/apiError';
import { submitShortcutLabel } from '../../../utils/platform';
import { useAuthStore } from '../../../stores/authStore';
import type { Notify } from '../../admin/layout/useToast';
import { isNewComment } from '../newSince';
import type { BuildPermalink } from '../useReviewPermalink';
import { useToggleCommentReaction } from '../reactions/useReactions';
import { avatarSrc } from '../../../utils/avatarUrl';
import { FullscreenComposerDialog } from '../markdown/FullscreenComposerDialog';
import { MarkdownComposer } from '../markdown/MarkdownComposer';
import { useCommentAttachmentUpload } from '../markdown/useCommentAttachmentUpload';
import { AVATAR_SIZE, CommentMessage } from './CommentMessage';
import { UserAvatar } from '../../shell/UserAvatar';

/** Centre of the avatar column — where the timeline rail runs. */
const LINE_LEFT = AVATAR_SIZE / 2 - 1;
/** Left indent that aligns loose lines (notes, dividers) with the message column. */
const CONTENT_INDENT = `${AVATAR_SIZE + 10}px`;

interface CommentThreadProps {
  annotationId: string;
  notify: Notify;
  /** Enables attaching local files to replies (issue #446) — the upload is document-scoped. */
  documentId?: string;
  /** Hides the reply composer — older versions are a read-only record (#306). */
  readOnly?: boolean;
  /**
   * READ_ONLY thread policy (issue #413): the thread is visible but the viewer is neither its
   * author nor the owner, so the composer gives way to a quiet "only author and owner" line.
   */
  policyReadOnly?: boolean;
  /** True on a settled (RESOLVED/DISMISSED) annotation (#403): the thread is a closed record. */
  closed?: boolean;
  /** True when the settlement was a dismissal (issue #408) — the closing line says so. */
  dismissed?: boolean;
  /** Reopens the annotation (issues #394/#408) — set only when the viewer may. */
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
 * The annotation's discussion — Slack's message anatomy (issue #445) married
 * to the timeline of the original design (issue #403): a vertical rail runs
 * through the avatar column and connects the root card to every reply and the
 * composer; each comment is a full-width message row with the bold author name
 * and the compact relative timestamp on its header line (full date in the
 * tooltip). Replies are written in the shared Slack-style MarkdownComposer.
 * Author names are resolved server-side and travel on each comment (issue
 * #413), honouring per-review anonymity; own contributions read "You".
 */
export function CommentThread({
  annotationId,
  notify,
  documentId,
  readOnly = false,
  policyReadOnly = false,
  closed = false,
  dismissed = false,
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
  // For the author hover cards (issue #482): the anonymity gate reads the
  // review's flags from the already-cached document. No document, no cards —
  // realAuthorId treats a missing review as "expose nothing".
  const review = useDocument(documentId ?? '').data;
  // The @-mention roster for the reply composer (issue #462): user participants, empty in anonymous
  // reviews so the picker is disabled where identities are hidden.
  const participantsQuery = useParticipants(documentId ?? '', Boolean(documentId));
  const mentionCandidates = useMemo<MentionCandidate[]>(() => {
    if (!review || review.anonymous) return [];
    return (participantsQuery.data?.participants ?? [])
      .filter((participant) => participant.kind === ParticipantKind.User)
      .map((participant) => ({ id: participant.principalId, name: participant.displayName }));
  }, [participantsQuery.data, review]);
  const addComment = useAddComment(annotationId);
  const toggleReaction = useToggleCommentReaction(annotationId, notify);
  const uploadAttachment = useCommentAttachmentUpload(documentId, notify);
  const [draft, setDraft] = useState('');
  // The full-screen writing stage (issue #403 follow-up). The draft state
  // stays HERE, so it survives entering and leaving the stage.
  const [writingFullscreen, setWritingFullscreen] = useState(false);
  const pulseColor = alpha(theme.qnop.brand.blue, 0.5);

  const submit = (onDone?: () => void) => {
    const body = draft.trim();
    if (!body || addComment.isPending) return;
    addComment.mutate(body, {
      onSuccess: () => {
        setDraft('');
        onDone?.();
      },
      onError: (error) => {
        const code = apiErrorCode(error);
        notify(
          code === 'ANNOTATION_ALREADY_RESOLVED'
            ? 'The annotation is settled — its thread is closed.'
            : code === 'THREAD_READ_ONLY'
              ? 'Only the author and the owner can reply in this review.'
              : 'Could not add the comment.',
          'error',
        );
      },
    });
  };

  const sendAction = (onDone?: () => void) => (
    <Tooltip title={`Send (${submitShortcutLabel()})`}>
      <span>
        <IconButton
          size="small"
          aria-label="Comment"
          color="primary"
          onClick={() => submit(onDone)}
          disabled={!draft.trim() || addComment.isPending}
        >
          <SendHorizontal size={16} />
        </IconButton>
      </span>
    </Tooltip>
  );

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
      <Stack spacing={2} sx={{ pt: 1.25 }}>
        {commentsQuery.isPending && (
          <Stack sx={{ alignItems: 'center', py: 1 }}>
            <CircularProgress size={18} aria-label="Loading comments" />
          </Stack>
        )}
        {commentsQuery.isError && (
          <Typography variant="body2" color="error" sx={{ pl: CONTENT_INDENT }}>
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
                  sx={{ alignItems: 'center', pl: CONTENT_INDENT }}
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
              <CommentMessage
                name={name}
                own={own}
                avatarUrl={own ? avatarUrl : avatarSrc(comment.authorId)}
                hoverUserId={realAuthorId(review, userId, comment.authorId)}
                hoverUserSlug={comment.authorSlug}
                body={comment.body}
                createdAt={comment.createdAt}
                domId={`comment-${comment.id}`}
                permalinkUrl={buildPermalink?.(annotationId, comment.id)}
                notify={notify}
                reactions={comment.reactions}
                onToggleReaction={
                  readOnly
                    ? undefined
                    : (emoji, reacted) =>
                        toggleReaction.mutate({ commentId: comment.id, emoji, reacted })
                }
              />
            </Fragment>
          );
        })}
        {!commentsQuery.isPending && !commentsQuery.isError && visibleComments.length === 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ pl: CONTENT_INDENT }}>
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
            sx={{
              alignItems: 'center',
              pl: CONTENT_INDENT,
              color: 'text.secondary',
              minHeight: 26,
            }}
          >
            {dismissed ? (
              <CircleSlash size={13} aria-hidden color={theme.palette.warning.main} />
            ) : (
              <CircleCheck size={13} aria-hidden color={theme.palette.success.main} />
            )}
            <Typography variant="caption">
              {dismissed
                ? 'Dismissed — this thread is closed.'
                : 'Resolved — this thread is closed.'}
            </Typography>
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
            sx={{
              alignItems: 'center',
              pl: CONTENT_INDENT,
              color: 'text.secondary',
              minHeight: 26,
            }}
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
          <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-start' }}>
            <Box sx={{ position: 'relative', zIndex: 1, pt: 0.5 }}>
              <UserAvatar name={displayName ?? 'You'} size={AVATAR_SIZE} imageUrl={avatarUrl} />
            </Box>
            {/* The shared Slack-style writing surface (issue #445): formatting
                toolbar, roomy auto-growing field, emoji — send bottom-right. */}
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <MarkdownComposer
                value={draft}
                onChange={setDraft}
                onSubmit={() => submit()}
                inputAriaLabel="Add a comment"
                minRows={3}
                onUploadAttachment={uploadAttachment}
                onToggleFullscreen={() => setWritingFullscreen(true)}
                actions={sendAction()}
                mentionCandidates={mentionCandidates}
              />
            </Box>
          </Stack>
        )}
      </Stack>
      {/* The full-screen writing stage (issue #403 follow-up): the same
          controlled draft as a frameless editor filling the stage, with the
          whole discussion — opener included, timeline anatomy and all — in the
          resizable context rail. Sending closes the stage. */}
      <FullscreenComposerDialog
        open={writingFullscreen}
        onClose={() => setWritingFullscreen(false)}
        title="Write a reply"
        contextTitle={`Discussion (${comments.length})`}
        context={
          // documentId travels along so the author hover cards keep their
          // anonymity context in the fullscreen stage too (issue #482).
          <CommentThread
            annotationId={annotationId}
            documentId={documentId}
            notify={notify}
            readOnly
            previousSeenAt={previousSeenAt}
          />
        }
      >
        <MarkdownComposer
          value={draft}
          onChange={setDraft}
          onSubmit={() => submit(() => setWritingFullscreen(false))}
          inputAriaLabel="Add a comment"
          bare
          onUploadAttachment={uploadAttachment}
          fullscreen
          onToggleFullscreen={() => setWritingFullscreen(false)}
          mentionCandidates={mentionCandidates}
          actions={
            <Button
              size="small"
              variant="contained"
              onClick={() => submit(() => setWritingFullscreen(false))}
              disabled={!draft.trim() || addComment.isPending}
            >
              Comment ({submitShortcutLabel()})
            </Button>
          }
        />
      </FullscreenComposerDialog>
    </Box>
  );
}
