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
import { ConfirmDialog } from '../../admin/ConfirmDialog';
import type { Notify } from '../../admin/layout/useToast';
import { UserAvatar } from '../../shell/UserAvatar';
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
  isOwner: boolean;
  ownUserId: string | null;
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
 * The review hub controls in the page header (#251): decided/total progress,
 * the participant stack with its management dialog, workflow transitions
 * (POST is authoritative — guard vetoes surface as toasts, ADR-0011) and the
 * owner-only new-version upload.
 */
export function ReviewHubHead({
  documentId,
  isOwner,
  ownUserId,
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
  const transitions = workflowQuery.data?.allowedTransitions ?? [];
  const maxSizeMb = config?.upload.maxDocumentSizeMb ?? FALLBACK_MAX_SIZE_MB;

  const total = annotations.length;
  const decided = annotations.filter((a) => a.status !== AnnotationStatus.Open).length;

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
      {total > 0 && (
        <ProgressBar
          decided={decided}
          total={total}
          color={theme.palette.success.main}
          discussion={
            annotations.filter((a) => a.status === AnnotationStatus.Open && a.commentCount > 1)
              .length
          }
        />
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
                    <UserAvatar name={participant.displayName} size={24} />
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

      {transitions.length > 0 && (
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
            {transitions.map((target) => (
              <MenuItem
                key={target}
                onClick={() => {
                  setWorkflowMenuAnchor(null);
                  setConfirmTarget(target);
                }}
              >
                Move to {workflowLabel(target)}
              </MenuItem>
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
            ? `Move this review to "${workflowLabel(confirmTarget)}"? Reviewers will see the new status immediately.`
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
