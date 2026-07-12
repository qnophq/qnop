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
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQueryClient } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, Rocket } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { PrincipalView } from '../../api/generated';
import { ParticipantKind, ThreadParticipation } from '../../api/generated';
import { documentsApi, reviewWorkflowApi } from '../../api/config';
import { useConfig } from '../../api/hooks/useConfig';
import { reviewKeys, useCreateReview } from '../../api/hooks/useReviews';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { DocumentStep } from '../../components/reviews/wizard/DocumentStep';
import { LaunchChecklist } from '../../components/reviews/wizard/LaunchChecklist';
import { ReviewerStep } from '../../components/reviews/wizard/ReviewerStep';
import { SummaryStep } from '../../components/reviews/wizard/SummaryStep';
import type { SubmitPhase } from '../../components/reviews/wizard/SummaryStep';
import { WizardStepsHeader } from '../../components/reviews/wizard/WizardStepsHeader';
import {
  launchChecklist,
  suggestSlug,
  titleFromFilename,
  validateDocumentFile,
  validateSlug,
} from '../../components/reviews/wizard/wizardModel';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage, apiFieldErrors } from '../../utils/apiError';

const STEPS = [{ label: 'Document' }, { label: 'Reviewers' }, { label: 'Summary & start' }];
const FALLBACK_MAX_SIZE_MB = 50;

interface SubmitState {
  phase: SubmitPhase;
  progress: number;
  error: string | null;
  /** Set when the review exists but a follow-up step (reviewer, start) failed. */
  partial: { documentId: string; failures: string[] } | null;
}

const SUBMIT_IDLE: SubmitState = { phase: 'idle', progress: 0, error: null, partial: null };

/** The guided 3-step new-review wizard at /reviews/new (#251). */
export function NewReviewPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.userId);
  const { data: config } = useConfig();
  const createReview = useCreateReview();

  const [step, setStep] = useState(1);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [slugError, setSlugError] = useState<string | null>(null);
  const [reviewers, setReviewers] = useState<PrincipalView[]>([]);
  const [dueAt, setDueAt] = useState<string | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  const [threadParticipation, setThreadParticipation] = useState<ThreadParticipation>(
    ThreadParticipation.Open,
  );
  const [startImmediately, setStartImmediately] = useState(true);
  const [submit, setSubmit] = useState<SubmitState>(SUBMIT_IDLE);

  const maxSizeMb = config?.upload.maxDocumentSizeMb ?? FALLBACK_MAX_SIZE_MB;
  const isSubmitting = submit.phase !== 'idle';

  // Until the user edits the slug themselves it mirrors the title (issue #411).
  const handleTitleChange = (value: string) => {
    setTitle(value);
    if (!slugTouched) {
      setSlug(suggestSlug(value));
      setSlugError(null);
    }
  };

  const handleFilePicked = (picked: File) => {
    const error = validateDocumentFile(picked, maxSizeMb);
    if (error) {
      setFileError(error);
      return;
    }
    setFileError(null);
    setFile(picked);
    if (title.trim() === '') handleTitleChange(titleFromFilename(picked.name));
  };

  // Submit-then-validate (house pattern): typing only clears a stale error;
  // the check runs when leaving step 1 and again server-side on create.
  const handleSlugChange = (value: string) => {
    setSlugTouched(value !== '');
    setSlug(value);
    setSlugError(null);
  };

  const addReviewer = (principal: PrincipalView) =>
    setReviewers((current) => [...current, principal]);
  const removeReviewer = (principal: PrincipalView) =>
    setReviewers((current) =>
      current.filter((p) => !(p.kind === principal.kind && p.id === principal.id)),
    );

  const canProceed = step !== 1 || (file !== null && title.trim() !== '');

  // The gamified sidekick (issue #469): every tick derives from live form state.
  const checklist = launchChecklist({
    hasFile: file !== null,
    title,
    slug,
    reviewerCount: reviewers.length,
    dueAt,
    startImmediately,
  });

  const handleNext = () => {
    if (step === 1) {
      const error = validateSlug(slug.trim().toLowerCase());
      setSlugError(error);
      if (error) return;
    }
    setStep(step + 1);
  };

  const handleCreate = async () => {
    if (!file) return;
    const cleanSlug = slug.trim().toLowerCase();
    setSubmit({ ...SUBMIT_IDLE, phase: 'uploading' });
    let documentId: string;
    try {
      const created = await createReview.mutateAsync({
        title: title.trim(),
        file,
        dueAt,
        slug: cleanSlug || null,
        anonymous,
        threadParticipation,
        onProgress: (fraction) => setSubmit((s) => ({ ...s, progress: fraction })),
      });
      documentId = created.documentId;
    } catch (error) {
      // A slug rejection (taken or malformed, issue #411) belongs on its field —
      // jump back to step 1 so the user sees the offending input.
      const slugFieldError = apiFieldErrors(error).slug;
      if (slugFieldError) {
        setSlugError(slugFieldError);
        setSubmit(SUBMIT_IDLE);
        setStep(1);
        return;
      }
      setSubmit({
        ...SUBMIT_IDLE,
        error: apiErrorMessage(error, 'The upload failed. Please try again.'),
      });
      return;
    }

    // The review now exists — follow-up failures must not look like a failed
    // creation, so they are collected and reported as a partial result.
    setSubmit((s) => ({ ...s, phase: 'finalizing', progress: 1 }));
    const failures: string[] = [];
    for (const principal of reviewers) {
      try {
        await documentsApi.addParticipant({
          documentId,
          participantCreateRequest:
            principal.kind === ParticipantKind.Team
              ? { teamId: principal.id }
              : { userId: principal.id },
        });
      } catch {
        failures.push(`add reviewer “${principal.displayName}”`);
      }
    }
    if (startImmediately) {
      try {
        await reviewWorkflowApi.transitionDocumentWorkflow({
          documentId,
          workflowTransitionRequest: { targetState: 'IN_REVIEW' },
        });
      } catch {
        failures.push('start the review');
      }
    }
    queryClient.invalidateQueries({ queryKey: reviewKeys.all });

    if (failures.length > 0) {
      setSubmit({ ...SUBMIT_IDLE, partial: { documentId, failures } });
      return;
    }
    navigate(`/reviews/${cleanSlug || documentId}`);
  };

  if (submit.partial) {
    return (
      <Stack spacing={3} sx={{ maxWidth: 720, mx: 'auto' }}>
        <PageHeader title="Review created" />
        <Alert severity="warning">
          The review was created, but some steps could not be completed:{' '}
          {submit.partial.failures.join(', ')}. You can finish these from the review page.
        </Alert>
        <Box>
          <Button
            variant="contained"
            onClick={() => navigate(`/reviews/${submit.partial?.documentId}`)}
          >
            Open review
          </Button>
        </Box>
      </Stack>
    );
  }

  return (
    <Stack spacing={3}>
      <PageHeader
        title="New review"
        description="Guided three-step setup: document, reviewers, start."
      />

      <Box
        sx={{
          display: 'grid',
          gap: 2.5,
          gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) 320px' },
          alignItems: 'start',
        }}
      >
        <Stack spacing={3} sx={{ minWidth: 0 }}>
          <WizardStepsHeader steps={STEPS} active={step} />

          {submit.error && <Alert severity="error">{submit.error}</Alert>}

          <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3.5 } }}>
            {step === 1 && (
              <DocumentStep
                file={file}
                title={title}
                fileError={fileError}
                slug={slug}
                slugError={slugError}
                maxSizeMb={maxSizeMb}
                onFilePicked={handleFilePicked}
                onFileCleared={() => setFile(null)}
                onTitleChange={handleTitleChange}
                onSlugChange={handleSlugChange}
              />
            )}
            {step === 2 && (
              <ReviewerStep
                selected={reviewers}
                ownUserId={userId}
                onAdd={addReviewer}
                onRemove={removeReviewer}
              />
            )}
            {step === 3 && file && (
              <SummaryStep
                file={file}
                title={title.trim()}
                reviewers={reviewers}
                dueAt={dueAt}
                onDueAtChange={setDueAt}
                startImmediately={startImmediately}
                onStartImmediatelyChange={setStartImmediately}
                anonymous={anonymous}
                onAnonymousChange={setAnonymous}
                threadParticipation={threadParticipation}
                onThreadParticipationChange={setThreadParticipation}
                phase={submit.phase}
                progress={submit.progress}
              />
            )}
          </Paper>

          <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <Button
              color="inherit"
              startIcon={<ChevronLeft size={16} />}
              disabled={isSubmitting}
              onClick={() => (step > 1 ? setStep(step - 1) : navigate('/reviews'))}
            >
              {step > 1 ? 'Back' : 'Cancel'}
            </Button>
            <Typography variant="caption" color="text.secondary">
              Step {step} of {STEPS.length}
            </Typography>
            {step < STEPS.length ? (
              <Button
                variant="contained"
                endIcon={<ChevronRight size={16} />}
                disabled={!canProceed}
                onClick={handleNext}
              >
                Next
              </Button>
            ) : (
              <Button
                variant="contained"
                startIcon={<Rocket size={16} />}
                disabled={isSubmitting}
                onClick={handleCreate}
              >
                {startImmediately ? 'Create & start review' : 'Create review'}
              </Button>
            )}
          </Stack>
        </Stack>

        {/* The mission rail: sticks alongside while the form scrolls. */}
        <Box sx={{ position: { lg: 'sticky' }, top: { lg: 16 }, minWidth: 0 }}>
          <LaunchChecklist items={checklist} />
        </Box>
      </Box>
    </Stack>
  );
}
