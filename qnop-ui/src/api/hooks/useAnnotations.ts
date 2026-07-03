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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  AnnotationCreateRequest,
  AnnotationDecision,
  AnnotationListResponse,
} from '../generated';
import { annotationsApi } from '../config';

export const annotationKeys = {
  all: ['annotations'] as const,
  list: (documentId: string, version?: number) =>
    [...annotationKeys.all, 'list', documentId, version] as const,
};

/**
 * The document's annotations with each placement (anchor + placement status)
 * resolved against the given 1-based version (ADR-0009: identity is
 * version-independent, physical location is per version).
 */
export function useAnnotations(documentId: string, version: number | undefined) {
  return useQuery<AnnotationListResponse>({
    queryKey: annotationKeys.list(documentId, version),
    queryFn: async () => {
      const response = await annotationsApi.listAnnotations({ documentId, version });
      return response.data;
    },
    enabled: version !== undefined && version >= 1,
  });
}

/** Creates an annotation on the drawn version; the placement is PLACED immediately. */
export function useCreateAnnotation(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: AnnotationCreateRequest) => {
      const response = await annotationsApi.createAnnotation({
        documentId,
        annotationCreateRequest: request,
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: annotationKeys.all }),
  });
}

/**
 * Decides an annotation (owner or author): OPEN -> ACCEPTED | REJECTED. The
 * first acceptance moves the workflow to CHANGES_REQUESTED (ADR-0011), so the
 * reviews/workflow caches are invalidated alongside the annotation lists.
 */
export function useDecideAnnotation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { annotationId: string; decision: AnnotationDecision }) => {
      const response = await annotationsApi.decideAnnotation({
        annotationId: vars.annotationId,
        annotationDecisionRequest: { decision: vars.decision },
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
      queryClient.invalidateQueries({ queryKey: ['reviews'] });
    },
  });
}
