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

import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { useResolveAnnotation } from '../../../api/hooks/useAnnotations';
import { apiErrorCode } from '../../../utils/apiError';
import type { Notify } from '../../admin/layout/useToast';

/** Known resolve conflicts (409) — mapped to friendly text, never server prose. */
const RESOLVE_CONFLICTS: Record<string, string> = {
  ANNOTATION_ALREADY_RESOLVED: 'This annotation was already resolved.',
};

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
  const resolve = useResolveAnnotation();
  const resolveWith = (annotation: AnnotationView, note?: string) => {
    resolve.mutate(
      { annotationId: annotation.id, note: note?.trim() || undefined },
      {
        onSuccess: () => notify('Annotation resolved.'),
        onError: (error) =>
          notify(
            RESOLVE_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'The annotation could not be resolved.',
            'error',
          ),
      },
    );
  };
  return { resolveWith, isPending: resolve.isPending };
}
