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
import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import Button from '@mui/material/Button';
import ButtonBase from '@mui/material/ButtonBase';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Archive, CalendarClock, ChevronDown, Trash2, Upload, UserPlus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, ParticipantKind } from '../../../api/generated';
import { useConfig } from '../../../api/hooks/useConfig';
import {
  useArchiveReview,
  useParticipants,
  useTransitionWorkflow,
  useWorkflow,
} from '../../../api/hooks/useReviews';
import { selectIsAdmin, useAuthStore } from '../../../stores/authStore';
import { ConfirmDialog } from '../../admin/ConfirmDialog';
import type { Notify } from '../../admin/layout/useToast';
import { UserHoverCard } from '../../people/UserHoverCard';
import { UserAvatar } from '../../shell/UserAvatar';
import { avatarSrc, teamAvatarSrc } from '../../../utils/avatarUrl';
import { TeamAvatar } from '../../shell/TeamAvatar';
import { DueDateLabel } from '../DueDateLabel';
import { ProgressBar } from '../list/ReviewListParts';
import { isOpenWorkflowState } from '../workflowMeta';
import { useWorkflowLabel } from '../useWorkflowLabel';
import type { ReviewsKey } from '../reviewsI18n';
import { acceptedUploads } from '../wizard/wizardModel';
import { apiErrorCode, apiErrorMessage } from '../../../utils/apiError';
import { DueDateDialog } from './DueDateDialog';
import { DeleteReviewDialog } from '../DeleteReviewDialog';
import { NewVersionDialog } from './NewVersionDialog';
import { ParticipantsDialog } from './ParticipantsDialog';

const FALLBACK_MAX_SIZE_MB = 50;
const MAX_STACK_AVATARS = 3;

/** Known 409 guard vetoes from the workflow choke-point (ADR-0011), as catalog keys. */
const TRANSITION_CONFLICTS: Record<string, ReviewsKey> = {
  INVALID_TRANSITION: 'hub.workflow.conflicts.INVALID_TRANSITION',
};

interface ReviewHubHeadProps {
  documentId: string;
  /** The review's title — typed back by the admin to confirm a deletion (issue #421). */
  title: string;
  /** The document owner — shown prominently in the header (issue #403). */
  ownerId: string;
  /** The owner's profile slug (issue #486) — structurally public (#472). */
  ownerSlug?: string | null;
  /** The owner's display name, resolved on the document (structurally public, #472). */
  ownerDisplayName?: string | null;
  isOwner: boolean;
  ownUserId: string | null;
  /** True for an anonymous review (issue #422) — the roster is anonymised for non-owners. */
  anonymous: boolean;
  annotations: AnnotationView[];
  /** The review's optional due date (ISO instant) or null (issue #295). */
  dueAt: string | null;
  /** The workflow state, so an overdue deadline is only flagged while open. */
  workflowState: string;
  /** When the review was archived (issue #576), or null — drives the Archive/Unarchive action. */
  archivedAt: string | null;
  notify: Notify;
  /** Called with the new version number after a successful re-upload. */
  onVersionUploaded: (versionNumber: number) => void;
  /** Called once the review has been deleted; there is nothing left to show (issue #421). */
  onDeleted: () => void;
}

/**
 * The review hub controls in the page header (#251): resolved/total progress,
 * the participant stack with its management dialog, workflow transitions
 * (POST is authoritative — guard vetoes surface as toasts, ADR-0011) and the
 * owner-only new-version upload.
 */
export function ReviewHubHead({
  documentId,
  title,
  ownerId,
  ownerSlug,
  ownerDisplayName,
  isOwner,
  ownUserId,
  anonymous,
  annotations,
  dueAt,
  workflowState,
  archivedAt,
  notify,
  onVersionUploaded,
  onDeleted,
}: ReviewHubHeadProps) {
  const theme = useTheme();
  const { t } = useTranslation(['reviews', 'common']);
  const workflowLabel = useWorkflowLabel();
  const [participantsOpen, setParticipantsOpen] = useState(false);
  const [newVersionOpen, setNewVersionOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [dueDateOpen, setDueDateOpen] = useState(false);
  const [workflowMenuAnchor, setWorkflowMenuAnchor] = useState<HTMLElement | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<string | null>(null);

  const isAdmin = useAuthStore(selectIsAdmin);
  const { data: config } = useConfig();
  const participantsQuery = useParticipants(documentId);
  const workflowQuery = useWorkflow(documentId);
  const transition = useTransitionWorkflow(documentId);
  const { archive, unarchive } = useArchiveReview(documentId);
  // A closed review (FINALIZED/CANCELLED) can be archived out of the active lists,
  // or restored if already archived (issue #576) — owner action.
  const isClosed = !isOpenWorkflowState(workflowState);

  const participants = participantsQuery.data?.participants ?? [];
  const ownDisplayName = useAuthStore((state) => state.displayName);
  const ownAvatarUrl = useAuthStore((state) => state.avatarUrl);
  // The owner never sits in the participant rows; their name travels on the
  // document itself (structurally public, #472) — never resolved via the
  // size-capped principal directory, which drops users in big workspaces.
  const ownerName =
    ownerId === ownUserId ? (ownDisplayName ?? t('you')) : (ownerDisplayName ?? t('roles.owner'));
  // Actor-scoped capability + guard-evaluated options (issue #568): reviewers
  // never see the affordance; blocked targets explain themselves.
  const mayTransition = workflowQuery.data?.mayTransition ?? false;
  const transitionOptions = workflowQuery.data?.transitions ?? [];
  const maxSizeMb = config?.upload.maxDocumentSizeMb ?? FALLBACK_MAX_SIZE_MB;
  const accepted = acceptedUploads(config?.supportedFormats, config?.supportedMediaTypes);

  const total = annotations.length;
  const resolved = annotations.filter((a) => a.status !== AnnotationStatus.Open).length;
  const openCount = total - resolved;

  // Terminal transitions settle open annotations automatically (issue #568) —
  // the confirm dialog must spell that consequence out.
  const autoCloseNote = (target: string): string => {
    if (openCount === 0) return '';
    const applies =
      target === 'CANCELLED' ||
      (target === 'FINALIZED' && (config?.review?.finalizeWithOpenAnnotations ?? false));
    if (!applies) return '';
    return ` ${t('hub.workflow.autoClose', { count: openCount })}`;
  };
  const inDiscussion = annotations.filter(
    (a) => a.status === AnnotationStatus.Open && a.commentCount > 1,
  ).length;

  const handleTransitionConfirmed = (targetState: string) => {
    setConfirmTarget(null);
    transition.mutate(targetState, {
      onSuccess: () => notify(t('hub.workflow.moved', { state: workflowLabel(targetState) })),
      onError: (error) => {
        const conflictKey = TRANSITION_CONFLICTS[apiErrorCode(error) ?? ''];
        notify(
          conflictKey ? t(conflictKey) : apiErrorMessage(error, t('hub.workflow.failed')),
          'error',
        );
      },
    });
  };

  const shown = participants.slice(0, MAX_STACK_AVATARS);

  return (
    // useFlexGap: with margin-based spacing a wrapped second row would sit
    // flush against the first — the wrap fires for real now that PageHeader
    // hands this bar a width limit (issue #774).
    <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
      {/* The owner is structurally public (issue #472), so the hover card
          (issue #482) may attach even in anonymous reviews — it replaces the
          old "Review owner" tooltip, which the OWNER label already spells. */}
      <UserHoverCard userId={ownerId} slug={ownerSlug} profileName={ownerName}>
        <Stack
          direction="row"
          spacing={0.75}
          data-testid="review-owner"
          sx={{ alignItems: 'center', minWidth: 0 }}
        >
          <UserAvatar
            name={ownerName}
            size={24}
            imageUrl={ownerId === ownUserId ? ownAvatarUrl : avatarSrc(ownerId)}
          />
          <Box sx={{ minWidth: 0 }}>
            <Typography
              sx={{
                fontSize: 9,
                fontWeight: 700,
                letterSpacing: '0.08em',
                textTransform: 'uppercase',
                color: 'text.secondary',
                lineHeight: 1.1,
              }}
            >
              {t('roles.owner')}
            </Typography>
            <Typography variant="body2" noWrap sx={{ fontWeight: 600, lineHeight: 1.2 }}>
              {ownerName}
            </Typography>
          </Box>
        </Stack>
      </UserHoverCard>
      <Divider orientation="vertical" flexItem sx={{ my: 0.5 }} />
      {total > 0 && (
        <Tooltip
          title={t(
            inDiscussion > 0
              ? 'hub.progress.inDiscussion'
              : openCount > 0
                ? 'hub.progress.stillOpen'
                : 'hub.progress.ready',
            { resolved, count: total, discussion: inDiscussion, open: openCount },
          )}
        >
          <Box sx={{ display: 'flex' }}>
            <ProgressBar
              resolved={resolved}
              total={total}
              color={theme.palette.success.main}
              discussion={inDiscussion}
            />
          </Box>
        </Tooltip>
      )}

      {isOwner ? (
        <Tooltip title={t('hub.dueDate.set')}>
          <ButtonBase
            onClick={() => setDueDateOpen(true)}
            data-testid="due-date-button"
            aria-label={t('hub.dueDate.set')}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.75,
              px: 1,
              py: 0.5,
              borderRadius: 99,
              '&:hover': { bgcolor: theme.qnop.surface2 },
              '&:focus-visible': { boxShadow: theme.qnop.focusRing },
            }}
          >
            {dueAt ? (
              <DueDateLabel dueAt={dueAt} workflowState={workflowState} />
            ) : (
              <>
                <CalendarClock
                  size={16}
                  aria-hidden
                  style={{ color: theme.palette.text.secondary }}
                />
                <Typography variant="caption" color="text.secondary">
                  {t('hub.dueDate.placeholder')}
                </Typography>
              </>
            )}
          </ButtonBase>
        </Tooltip>
      ) : (
        dueAt && <DueDateLabel dueAt={dueAt} workflowState={workflowState} />
      )}

      <Tooltip title={isOwner ? t('hub.participants.manage') : t('hub.participants.view')}>
        <ButtonBase
          onClick={() => setParticipantsOpen(true)}
          data-testid="participants-button"
          aria-label={isOwner ? t('hub.participants.manage') : t('hub.participants.view')}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.75,
            px: 1,
            py: 0.5,
            borderRadius: 99,
            '&:hover': { bgcolor: theme.qnop.surface2 },
            '&:focus-visible': { boxShadow: theme.qnop.focusRing },
          }}
        >
          {shown.length === 0 ? (
            <UserPlus size={16} aria-hidden style={{ color: theme.palette.text.secondary }} />
          ) : (
            <Stack direction="row" sx={{ alignItems: 'center' }}>
              {shown.map((participant, index) => (
                <Box
                  key={participant.id}
                  sx={{
                    borderRadius: '50%',
                    border: '2px solid',
                    borderColor: 'background.paper',
                    ml: index === 0 ? 0 : -0.75,
                    display: 'flex',
                    zIndex: shown.length - index,
                  }}
                >
                  {participant.kind === ParticipantKind.Team ? (
                    <TeamAvatar
                      name={participant.displayName}
                      size={24}
                      imageUrl={teamAvatarSrc(participant.principalId)}
                    />
                  ) : (
                    <UserAvatar
                      name={participant.displayName}
                      size={24}
                      imageUrl={avatarSrc(participant.principalId)}
                    />
                  )}
                </Box>
              ))}
            </Stack>
          )}
          <Typography variant="caption" color="text.secondary">
            {participants.length === 0
              ? isOwner
                ? t('hub.participants.add')
                : t('hub.participants.none')
              : participants.length}
          </Typography>
        </ButtonBase>
      </Tooltip>

      {mayTransition && transitionOptions.length > 0 && (
        <>
          <Button
            size="small"
            variant="outlined"
            color="inherit"
            endIcon={<ChevronDown size={14} />}
            disabled={transition.isPending}
            onClick={(e) => setWorkflowMenuAnchor(e.currentTarget)}
          >
            {t('hub.workflow.change')}
          </Button>
          <Menu
            open={Boolean(workflowMenuAnchor)}
            anchorEl={workflowMenuAnchor}
            onClose={() => setWorkflowMenuAnchor(null)}
          >
            {transitionOptions.map((option) => (
              // A blocked target stays visible and explains itself (issue
              // #568) — a silently missing FINALIZED read as a bug.
              <Tooltip
                key={option.targetState}
                title={option.available ? '' : (option.blockedReason ?? '')}
                placement="left"
              >
                <span>
                  <MenuItem
                    disabled={!option.available}
                    onClick={() => {
                      setWorkflowMenuAnchor(null);
                      setConfirmTarget(option.targetState);
                    }}
                  >
                    {t('hub.workflow.moveTo', { state: workflowLabel(option.targetState) })}
                  </MenuItem>
                </span>
              </Tooltip>
            ))}
          </Menu>
        </>
      )}

      {isOwner && isClosed && (
        <Button
          size="small"
          variant="outlined"
          color="inherit"
          startIcon={<Archive size={14} />}
          disabled={archive.isPending || unarchive.isPending}
          onClick={() =>
            archivedAt
              ? unarchive.mutate(undefined, {
                  onSuccess: () => notify(t('hub.archive.restored')),
                  onError: () => notify(t('hub.archive.unarchiveFailed'), 'error'),
                })
              : archive.mutate(undefined, {
                  onSuccess: () => notify(t('hub.archive.archived')),
                  onError: () => notify(t('hub.archive.archiveFailed'), 'error'),
                })
          }
        >
          {archivedAt ? t('hub.archive.unarchive') : t('hub.archive.archive')}
        </Button>
      )}

      {isAdmin && (
        // Deleting is the admin's alone (issue #421): the owner's tool beside it
        // is Archive, which is reversible and keeps the record.
        <Button
          size="small"
          variant="outlined"
          color="error"
          startIcon={<Trash2 size={14} />}
          onClick={() => setDeleteOpen(true)}
        >
          {t('common:actions.delete')}
        </Button>
      )}

      {isOwner && (
        <>
          <Button
            size="small"
            variant="outlined"
            startIcon={<Upload size={14} />}
            onClick={() => setNewVersionOpen(true)}
          >
            {t('hub.newVersion')}
          </Button>
        </>
      )}

      <DeleteReviewDialog
        documentId={documentId}
        title={title}
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        annotationCount={annotations.length}
        onDeleted={onDeleted}
      />

      <NewVersionDialog
        documentId={documentId}
        open={newVersionOpen}
        onClose={() => setNewVersionOpen(false)}
        maxSizeMb={maxSizeMb}
        accepted={accepted}
        notify={notify}
        onUploaded={onVersionUploaded}
      />

      <ParticipantsDialog
        documentId={documentId}
        open={participantsOpen}
        onClose={() => setParticipantsOpen(false)}
        isOwner={isOwner}
        ownUserId={ownUserId}
        anonymised={anonymous && !isOwner}
        notify={notify}
      />

      {isOwner && (
        <DueDateDialog
          documentId={documentId}
          open={dueDateOpen}
          onClose={() => setDueDateOpen(false)}
          dueAt={dueAt}
          notify={notify}
        />
      )}

      <ConfirmDialog
        open={confirmTarget !== null}
        title={t('hub.workflow.confirmTitle')}
        message={
          confirmTarget
            ? t('hub.workflow.confirmMessage', { state: workflowLabel(confirmTarget) }) +
              autoCloseNote(confirmTarget)
            : ''
        }
        confirmLabel={t('hub.workflow.change')}
        destructive={confirmTarget === 'CANCELLED'}
        onConfirm={() => confirmTarget && handleTransitionConfirmed(confirmTarget)}
        onClose={() => setConfirmTarget(null)}
      />
    </Stack>
  );
}
