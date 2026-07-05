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
import { AnnotationDecision, AnnotationStatus } from '../../../api/generated';
import { useDecideAnnotation } from '../../../api/hooks/useAnnotations';
import { apiErrorCode } from '../../../utils/apiError';
import type { Notify } from '../../admin/layout/useToast';

/** Known decision conflicts (409) — mapped to friendly text, never server prose. */
const DECISION_CONFLICTS: Record<string, string> = {
  ANNOTATION_ALREADY_DECIDED: 'This annotation was already decided.',
};

/**
 * Only the OWNER decides an OPEN annotation — issue #403 tightened the
 * original owner-or-author rule (#247): reviewers raise and discuss, the
 * owner rules. Mirrors the ReviewWorkflowService guard.
 */
export function mayDecideAnnotation(
  annotation: AnnotationView,
  userId: string | null,
  ownerId: string | null,
): boolean {
  return annotation.status === AnnotationStatus.Open && userId !== null && ownerId === userId;
}

/**
 * The decide mutation with the shared toast feedback — used by the panel and
 * the focus overlay card, so both surfaces report identically.
 */
export function useDecideWithFeedback(notify: Notify) {
  const decide = useDecideAnnotation();
  const decideWith = (annotation: AnnotationView, decision: AnnotationDecision) => {
    decide.mutate(
      { annotationId: annotation.id, decision },
      {
        onSuccess: () =>
          notify(
            decision === AnnotationDecision.Accepted
              ? 'Annotation accepted.'
              : 'Annotation rejected.',
          ),
        onError: (error) =>
          notify(
            DECISION_CONFLICTS[apiErrorCode(error) ?? ''] ?? 'The decision could not be saved.',
            'error',
          ),
      },
    );
  };
  return { decideWith, isPending: decide.isPending };
}
