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
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import type { PrincipalView } from '../../api/generated';
import { ParticipantKind, ThreadParticipation } from '../../api/generated';
import { documentsApi, reviewWorkflowApi } from '../../api/config';
import { useConfig } from '../../api/hooks/useConfig';
import { reviewKeys, useCreateReview, useReviewCapacity } from '../../api/hooks/useReviews';
import { QuotaAlert } from '../../components/limits/QuotaAlert';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { CommanderCard } from '../../components/reviews/wizard/CommanderCard';
import { DocumentStep } from '../../components/reviews/wizard/DocumentStep';
import { LaunchChecklist } from '../../components/reviews/wizard/LaunchChecklist';
import { ReviewerStep } from '../../components/reviews/wizard/ReviewerStep';
import { SummaryStep } from '../../components/reviews/wizard/SummaryStep';
import type { SubmitPhase } from '../../components/reviews/wizard/SummaryStep';
import { WizardStepsHeader } from '../../components/reviews/wizard/WizardStepsHeader';
import {
  acceptedUploads,
  launchChecklist,
  suggestSlug,
  titleFromFilename,
  validateDocumentFile,
  validateSlug,
} from '../../components/reviews/wizard/wizardModel';
import {
  renderReviewsPhrase,
  type ReviewsKey,
  type ReviewsPhrase,
} from '../../components/reviews/reviewsI18n';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorMessage, apiFieldErrors } from '../../utils/apiError';

const STEPS: ReviewsKey[] = [
  'wizard.steps.document',
  'wizard.steps.reviewers',
  'wizard.steps.summary',
];
const FALLBACK_MAX_SIZE_MB = 50;

/** A follow-up step that failed after the review was created — worded at render. */
type FollowUpFailure = { kind: 'addReviewer'; name: string } | { kind: 'start' };

/**
 * A slug rejection: the client-side check's phrase, or the server's refusal, whose field error
 * is worded from the cause at render (issue #836) so it follows a language switch.
 */
type SlugError = { phrase: ReviewsPhrase } | { cause: unknown };

interface SubmitState {
  phase: SubmitPhase;
  progress: number;
  /** The cause of a failed upload, worded at render; null when nothing failed. */
  error: { cause: unknown } | null;
  /** Set when the review exists but a follow-up step (reviewer, start) failed. */
  partial: { documentId: string; failures: FollowUpFailure[] } | null;
}

const SUBMIT_IDLE: SubmitState = { phase: 'idle', progress: 0, error: null, partial: null };

/** The guided 3-step new-review wizard at /reviews/new (#251). */
export function NewReviewPage() {
  const { t } = useTranslation(['reviews', 'common']);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.userId);
  const { data: config } = useConfig();
  const createReview = useCreateReview();
  const capacity = useReviewCapacity();

  const [step, setStep] = useState(1);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<ReviewsPhrase | null>(null);
  const [title, setTitle] = useState('');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [slugError, setSlugError] = useState<SlugError | null>(null);
  const [reviewers, setReviewers] = useState<PrincipalView[]>([]);
  const [dueAt, setDueAt] = useState<string | null>(null);
  const [anonymous, setAnonymous] = useState(false);
  const [threadParticipation, setThreadParticipation] = useState<ThreadParticipation>(
    ThreadParticipation.Open,
  );
  const [startImmediately, setStartImmediately] = useState(true);
  const [submit, setSubmit] = useState<SubmitState>(SUBMIT_IDLE);

  const maxSizeMb = config?.upload.maxDocumentSizeMb ?? FALLBACK_MAX_SIZE_MB;
  const slugErrorText =
    slugError === null
      ? null
      : 'phrase' in slugError
        ? renderReviewsPhrase(slugError.phrase, t)
        : (apiFieldErrors(slugError.cause).slug ?? null);
  const accepted = acceptedUploads(config?.supportedFormats, config?.supportedMediaTypes);
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
    const error = validateDocumentFile(picked, maxSizeMb, accepted);
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
      setSlugError(error ? { phrase: error } : null);
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
        setSlugError({ cause: error });
        setSubmit(SUBMIT_IDLE);
        setStep(1);
        return;
      }
      setSubmit({ ...SUBMIT_IDLE, error: { cause: error } });
      return;
    }

    // The review now exists — follow-up failures must not look like a failed
    // creation, so they are collected and reported as a partial result.
    setSubmit((s) => ({ ...s, phase: 'finalizing', progress: 1 }));
    const failures: FollowUpFailure[] = [];
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
        failures.push({ kind: 'addReviewer', name: principal.displayName });
      }
    }
    if (startImmediately) {
      try {
        await reviewWorkflowApi.transitionDocumentWorkflow({
          documentId,
          workflowTransitionRequest: { targetState: 'IN_REVIEW' },
        });
      } catch {
        failures.push({ kind: 'start' });
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
    const failures = submit.partial.failures
      .map((failure) =>
        failure.kind === 'addReviewer'
          ? t('wizard.created.addReviewer', { name: failure.name })
          : t('wizard.created.start'),
      )
      .join(', ');
    return (
      <Stack spacing={3} sx={{ maxWidth: 720, mx: 'auto' }}>
        <PageHeader title={t('wizard.created.title')} />
        <Alert severity="warning">{t('wizard.created.partial', { failures })}</Alert>
        <Box>
          <Button
            variant="contained"
            onClick={() => navigate(`/reviews/${submit.partial?.documentId}`)}
          >
            {t('wizard.created.openReview')}
          </Button>
        </Box>
      </Stack>
    );
  }

  // A wizard that cannot end in a review is worse than no wizard (issue #692):
  // three steps of uploading, picking reviewers and setting a deadline, only to
  // be refused at the last one. The ingest endpoint refuses either way; this is
  // about not asking for the work first.
  //
  // Not an error page: unlike a withheld capability (#682) this clears itself
  // the moment somebody finalizes a review, so it says what to do rather than
  // showing a wall.
  if (capacity.full) {
    return (
      <Stack spacing={3}>
        <PageHeader title={t('page.newReview')} description={t('wizard.description')} />
        <QuotaAlert>
          {t('wizard.quotaFull', { count: capacity.limit, used: capacity.used })}
        </QuotaAlert>
        <Box>
          <Button variant="contained" onClick={() => navigate('/reviews')}>
            {t('actions.backToReviews')}
          </Button>
        </Box>
      </Stack>
    );
  }

  return (
    <Stack spacing={3}>
      <PageHeader title={t('page.newReview')} description={t('wizard.description')} />

      <Box
        sx={{
          display: 'grid',
          gap: 2.5,
          gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) 320px' },
          alignItems: 'start',
        }}
      >
        <Stack spacing={3} sx={{ minWidth: 0 }}>
          <WizardStepsHeader steps={STEPS.map((key) => ({ label: t(key) }))} active={step} />

          {submit.error && (
            <Alert severity="error">
              {apiErrorMessage(submit.error.cause, t('wizard.uploadFailed'))}
            </Alert>
          )}

          <Paper variant="outlined" sx={{ p: { xs: 2.5, sm: 3.5 } }}>
            {step === 1 && (
              <DocumentStep
                file={file}
                title={title}
                fileError={fileError && renderReviewsPhrase(fileError, t)}
                slug={slug}
                slugError={slugErrorText}
                maxSizeMb={maxSizeMb}
                accepted={accepted}
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
              {step > 1 ? t('actions.back') : t('common:actions.cancel')}
            </Button>
            <Typography variant="caption" color="text.secondary">
              {t('wizard.stepOf', { step, total: STEPS.length })}
            </Typography>
            {step < STEPS.length ? (
              <Button
                variant="contained"
                endIcon={<ChevronRight size={16} />}
                disabled={!canProceed}
                onClick={handleNext}
              >
                {t('actions.next')}
              </Button>
            ) : (
              <Button
                variant="contained"
                startIcon={<Rocket size={16} />}
                disabled={isSubmitting}
                onClick={handleCreate}
              >
                {startImmediately ? t('wizard.createAndStart') : t('wizard.create')}
              </Button>
            )}
          </Stack>
        </Stack>

        {/* The mission rail: sticks alongside while the form scrolls. */}
        <Stack spacing={2.5} sx={{ position: { lg: 'sticky' }, top: { lg: 16 }, minWidth: 0 }}>
          <CommanderCard />
          <LaunchChecklist items={checklist} />
        </Stack>
      </Box>
    </Stack>
  );
}
