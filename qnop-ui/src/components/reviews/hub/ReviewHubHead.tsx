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

import { useRef, useState } from 'react';
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
import { CalendarClock, ChevronDown, Upload, UserPlus, Users } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus, ParticipantKind } from '../../../api/generated';
import { useConfig } from '../../../api/hooks/useConfig';
import {
  useParticipants,
  useTransitionWorkflow,
  useUploadVersion,
  useWorkflow,
} from '../../../api/hooks/useReviews';
import { useAuthStore } from '../../../stores/authStore';
import { ConfirmDialog } from '../../admin/ConfirmDialog';
import type { Notify } from '../../admin/layout/useToast';
import { UserHoverCard } from '../../people/UserHoverCard';
import { UserAvatar } from '../../shell/UserAvatar';
import { avatarSrc } from '../../../utils/avatarUrl';
import { DueDateLabel } from '../DueDateLabel';
import { ProgressBar } from '../list/ReviewListParts';
import { workflowLabel } from '../workflowMeta';
import { validateDocumentFile } from '../wizard/wizardModel';
import { apiErrorCode, apiErrorMessage } from '../../../utils/apiError';
import { DueDateDialog } from './DueDateDialog';
import { ParticipantsDialog } from './ParticipantsDialog';

const FALLBACK_MAX_SIZE_MB = 50;
const MAX_STACK_AVATARS = 3;

/** Known 409 guard vetoes from the workflow choke-point (ADR-0011). */
const TRANSITION_CONFLICTS: Record<string, string> = {
  INVALID_TRANSITION:
    'This status change is not possible right now — open annotations or pending placements may remain.',
};

interface ReviewHubHeadProps {
  documentId: string;
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
  notify: Notify;
  /** Called with the new version number after a successful re-upload. */
  onVersionUploaded: (versionNumber: number) => void;
}

/**
 * The review hub controls in the page header (#251): resolved/total progress,
 * the participant stack with its management dialog, workflow transitions
 * (POST is authoritative — guard vetoes surface as toasts, ADR-0011) and the
 * owner-only new-version upload.
 */
export function ReviewHubHead({
  documentId,
  ownerId,
  ownerSlug,
  ownerDisplayName,
  isOwner,
  ownUserId,
  anonymous,
  annotations,
  dueAt,
  workflowState,
  notify,
  onVersionUploaded,
}: ReviewHubHeadProps) {
  const theme = useTheme();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [participantsOpen, setParticipantsOpen] = useState(false);
  const [dueDateOpen, setDueDateOpen] = useState(false);
  const [workflowMenuAnchor, setWorkflowMenuAnchor] = useState<HTMLElement | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<string | null>(null);

  const { data: config } = useConfig();
  const participantsQuery = useParticipants(documentId);
  const workflowQuery = useWorkflow(documentId);
  const transition = useTransitionWorkflow(documentId);
  const uploadVersion = useUploadVersion(documentId);

  const participants = participantsQuery.data?.participants ?? [];
  const ownDisplayName = useAuthStore((state) => state.displayName);
  const ownAvatarUrl = useAuthStore((state) => state.avatarUrl);
  // The owner never sits in the participant rows; their name travels on the
  // document itself (structurally public, #472) — never resolved via the
  // size-capped principal directory, which drops users in big workspaces.
  const ownerName =
    ownerId === ownUserId ? (ownDisplayName ?? 'You') : (ownerDisplayName ?? 'Owner');
  // Actor-scoped capability + guard-evaluated options (issue #568): reviewers
  // never see the affordance; blocked targets explain themselves.
  const mayTransition = workflowQuery.data?.mayTransition ?? false;
  const transitionOptions = workflowQuery.data?.transitions ?? [];
  const maxSizeMb = config?.upload.maxDocumentSizeMb ?? FALLBACK_MAX_SIZE_MB;

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
    const noun = openCount === 1 ? 'open annotation' : 'open annotations';
    return ` ${openCount} ${noun} will be closed automatically with a standard comment.`;
  };
  const inDiscussion = annotations.filter(
    (a) => a.status === AnnotationStatus.Open && a.commentCount > 1,
  ).length;

  const handleTransitionConfirmed = (targetState: string) => {
    setConfirmTarget(null);
    transition.mutate(targetState, {
      onSuccess: () => notify(`Review moved to ${workflowLabel(targetState)}.`),
      onError: (error) =>
        notify(
          TRANSITION_CONFLICTS[apiErrorCode(error) ?? ''] ??
            apiErrorMessage(error, 'The status could not be changed.'),
          'error',
        ),
    });
  };

  const handleFilePicked = (file: File) => {
    const error = validateDocumentFile(file, maxSizeMb);
    if (error) {
      notify(error, 'error');
      return;
    }
    uploadVersion.mutate(
      { file },
      {
        onSuccess: (result) => {
          notify(`Version ${result.versionNumber} uploaded.`);
          onVersionUploaded(result.versionNumber);
        },
        onError: (uploadError) =>
          notify(apiErrorMessage(uploadError, 'The new version could not be uploaded.'), 'error'),
      },
    );
  };

  const shown = participants.slice(0, MAX_STACK_AVATARS);

  return (
    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
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
              Owner
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
          title={`Review progress: ${resolved} of ${total} annotation${total === 1 ? '' : 's'} resolved${
            inDiscussion > 0
              ? ` · ${inDiscussion} open in discussion`
              : total - resolved > 0
                ? ` · ${total - resolved} still open`
                : ' — ready to finalize'
          }`}
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
        <Tooltip title="Set review due date">
          <ButtonBase
            onClick={() => setDueDateOpen(true)}
            data-testid="due-date-button"
            aria-label="Set review due date"
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
                  Set due date
                </Typography>
              </>
            )}
          </ButtonBase>
        </Tooltip>
      ) : (
        dueAt && <DueDateLabel dueAt={dueAt} workflowState={workflowState} />
      )}

      <Tooltip title={isOwner ? 'Manage participants' : 'View participants'}>
        <ButtonBase
          onClick={() => setParticipantsOpen(true)}
          data-testid="participants-button"
          aria-label={isOwner ? 'Manage participants' : 'View participants'}
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
                    <Box
                      sx={{
                        width: 24,
                        height: 24,
                        borderRadius: '50%',
                        bgcolor: theme.qnop.surface2,
                        color: 'text.secondary',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <Users size={12} aria-hidden />
                    </Box>
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
                ? 'Add reviewers'
                : 'No reviewers'
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
            Change status
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
                    Move to {workflowLabel(option.targetState)}
                  </MenuItem>
                </span>
              </Tooltip>
            ))}
          </Menu>
        </>
      )}

      {isOwner && (
        <>
          <Button
            size="small"
            variant="outlined"
            startIcon={<Upload size={14} />}
            disabled={uploadVersion.isPending}
            onClick={() => fileInputRef.current?.click()}
          >
            {uploadVersion.isPending ? 'Uploading…' : 'New version'}
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept="application/pdf,.pdf"
            hidden
            data-testid="version-file-input"
            onChange={(e) => {
              const picked = e.target.files?.[0];
              if (picked) handleFilePicked(picked);
              e.target.value = '';
            }}
          />
        </>
      )}

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
        title="Change review status"
        message={
          confirmTarget
            ? `Move this review to "${workflowLabel(confirmTarget)}"? Reviewers will see the new status immediately.${autoCloseNote(confirmTarget)}`
            : ''
        }
        confirmLabel="Change status"
        destructive={confirmTarget === 'CANCELLED'}
        onConfirm={() => confirmTarget && handleTransitionConfirmed(confirmTarget)}
        onClose={() => setConfirmTarget(null)}
      />
    </Stack>
  );
}
