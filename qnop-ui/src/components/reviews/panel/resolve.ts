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

import type { ParseKeys } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import {
  useDismissAnnotation,
  useReopenAnnotation,
  useResolveAnnotation,
} from '../../../api/hooks/useAnnotations';
import { apiErrorCode } from '../../../utils/apiError';
import type { Notify } from '../../admin/layout/useToast';

type WorkspaceKey = ParseKeys<'workspace'>;

/**
 * Known resolve conflicts (409) — mapped to friendly catalog text (ADR-0063),
 * never server prose.
 */
const RESOLVE_CONFLICTS: Record<string, WorkspaceKey> = {
  ANNOTATION_ALREADY_RESOLVED: 'resolve.conflicts.ANNOTATION_ALREADY_RESOLVED',
};

/** The `workspace` catalog key reporting a failed resolve. */
export function resolveErrorKey(error: unknown): WorkspaceKey {
  return RESOLVE_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'resolve.failed';
}

/**
 * Only the AUTHOR resolves an OPEN annotation — issue #405 replaced the owner
 * decision of #403/#247: the author raised the concern, so only they know
 * when it is settled. Mirrors the ReviewWorkflowService guard.
 */
export function mayResolveAnnotation(annotation: AnnotationView, userId: string | null): boolean {
  return (
    annotation.status === AnnotationStatus.Open && userId !== null && annotation.authorId === userId
  );
}

/**
 * The resolve mutation with the shared toast feedback — used by the panel,
 * the focus overlay card and the task drawer, so all surfaces report
 * identically. An optional closing note travels along and lands in the
 * thread.
 */
export function useResolveWithFeedback(notify: Notify) {
  const { t } = useTranslation('workspace');
  const resolve = useResolveAnnotation();
  const resolveWith = (annotation: AnnotationView, note?: string) => {
    resolve.mutate(
      { annotationId: annotation.id, note: note?.trim() || undefined },
      {
        onSuccess: () => notify(t('resolve.done')),
        onError: (error) => notify(t(resolveErrorKey(error)), 'error'),
      },
    );
  };
  return { resolveWith, isPending: resolve.isPending };
}

/**
 * The AUTHOR reopens their settled annotation — RESOLVED (issue #394) or
 * DISMISSED, the objection right against the owner's dismissal (issue #408) —
 * and so does an ADMIN. The owner deliberately may NOT reverse their own
 * dismissal. The caller additionally gates on the review still running
 * (FINALIZED/CANCELLED are a closed record). Mirrors the
 * ReviewWorkflowService guard.
 */
export function mayReopenAnnotation(
  annotation: AnnotationView,
  userId: string | null,
  isAdmin = false,
): boolean {
  const settled =
    annotation.status === AnnotationStatus.Resolved ||
    annotation.status === AnnotationStatus.Dismissed;
  return settled && userId !== null && (isAdmin || annotation.authorId === userId);
}

/**
 * The OWNER (or an admin) dismisses someone else's OPEN annotation with a
 * justification (issue #408) — the escape hatch when an absent author blocks
 * the FINALIZED gate. Never offered on the viewer's own annotation: the
 * author's path is Resolve. Mirrors the ReviewWorkflowService guard.
 */
export function mayDismissAnnotation(
  annotation: AnnotationView,
  userId: string | null,
  ownerId: string | undefined,
  isAdmin: boolean,
): boolean {
  if (annotation.status !== AnnotationStatus.Open || userId === null) return false;
  if (annotation.authorId === userId) return false;
  return isAdmin || (ownerId !== undefined && ownerId === userId);
}

/** Known reopen conflicts (409) — mapped to friendly catalog text, never server prose. */
const REOPEN_CONFLICTS: Record<string, WorkspaceKey> = {
  REVIEW_CLOSED: 'reopen.conflicts.REVIEW_CLOSED',
  ANNOTATION_NOT_RESOLVED: 'reopen.conflicts.ANNOTATION_NOT_RESOLVED',
};

/** The `workspace` catalog key reporting a failed reopen. */
export function reopenErrorKey(error: unknown): WorkspaceKey {
  return REOPEN_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'reopen.failed';
}

/** The reopen mutation with the shared toast feedback (issue #394). */
export function useReopenWithFeedback(notify: Notify) {
  const { t } = useTranslation('workspace');
  const reopen = useReopenAnnotation();
  const reopenWith = (annotation: AnnotationView) => {
    reopen.mutate(
      { annotationId: annotation.id },
      {
        onSuccess: () => notify(t('reopen.done')),
        onError: (error) => notify(t(reopenErrorKey(error)), 'error'),
      },
    );
  };
  return { reopenWith, isPending: reopen.isPending };
}

/** Known dismiss conflicts (409) — mapped to friendly catalog text, never server prose. */
const DISMISS_CONFLICTS: Record<string, WorkspaceKey> = {
  ANNOTATION_NOT_OPEN: 'dismiss.conflicts.ANNOTATION_NOT_OPEN',
  REVIEW_CLOSED: 'dismiss.conflicts.REVIEW_CLOSED',
};

/** The `workspace` catalog key reporting a failed dismissal. */
export function dismissErrorKey(error: unknown): WorkspaceKey {
  return DISMISS_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'dismiss.failed';
}

/**
 * The dismiss mutation with the shared toast feedback (issue #408). The
 * mandatory justification travels along and lands in the thread.
 */
export function useDismissWithFeedback(notify: Notify) {
  const { t } = useTranslation('workspace');
  const dismiss = useDismissAnnotation();
  const dismissWith = (annotation: AnnotationView, justification: string) => {
    dismiss.mutate(
      { annotationId: annotation.id, justification: justification.trim() },
      {
        onSuccess: () => notify(t('dismiss.done')),
        onError: (error) => notify(t(dismissErrorKey(error)), 'error'),
      },
    );
  };
  return { dismissWith, isPending: dismiss.isPending };
}
