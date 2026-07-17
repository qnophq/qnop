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
import type { Anchor, AnnotationCreateRequest, AnnotationListResponse } from '../generated';
import { annotationsApi } from '../config';
import type { Notify } from '../../components/admin/layout/useToast';
import { commentKeys } from './useComments';
import { documentKeys } from './useDocuments';

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

/**
 * Creates an annotation on the drawn version; the placement is PLACED
 * immediately. Raising the first open annotation derives CHANGES_REQUESTED
 * (issue #405), so the workflow-bearing caches are invalidated too.
 */
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
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
      queryClient.invalidateQueries({ queryKey: ['reviews'] });
      // Detail only (workflow chip / counts) — NEVER documentKeys.all: that
      // sweeps in the rendered/original binaries and reloading the PDF
      // remounts the viewer, snapping the scroll back to page 1 (issue #403).
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
    },
  });
}

/**
 * Reopens a resolved annotation as its author: RESOLVED -> OPEN (issue #394).
 * A reopened concern re-derives CHANGES_REQUESTED, so the workflow-bearing
 * caches are invalidated too.
 */
export function useReopenAnnotation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { annotationId: string }) => {
      const response = await annotationsApi.reopenAnnotation({ annotationId: vars.annotationId });
      return response.data;
    },
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
      queryClient.invalidateQueries({ queryKey: ['reviews'] });
      // Detail only — see useCreateAnnotation: touching documentKeys.all
      // reloads the PDF and costs the reader their scroll position.
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(updated.documentId) });
    },
  });
}

/**
 * Resolves an annotation as its author: OPEN -> RESOLVED, with an optional
 * closing note that lands in the thread as a regular comment (issue #405).
 * Settling the last open annotation returns the workflow to IN_REVIEW, so the
 * reviews/workflow caches are invalidated alongside the annotation lists —
 * and the comment threads, because of the note.
 */
export function useResolveAnnotation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { annotationId: string; note?: string }) => {
      const response = await annotationsApi.resolveAnnotation({
        annotationId: vars.annotationId,
        annotationResolveRequest: vars.note ? { note: vars.note } : undefined,
      });
      return response.data;
    },
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
      queryClient.invalidateQueries({ queryKey: commentKeys.all });
      queryClient.invalidateQueries({ queryKey: ['reviews'] });
      // The derived workflow pair (#405) surfaces on the document detail
      // (the header's status chip reads document.workflowState) — detail
      // only, so the PDF/rendered caches stay put (issue #403).
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(updated.documentId) });
    },
  });
}

/**
 * Confirms a reviewed MOVED placement back to PLACED (ADR-0009, issue #326) —
 * the human half of re-anchoring, allowed for the owner or the annotation's
 * author on the version whose highlight was verified.
 */
export function useConfirmPlacement(notify: Notify) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { annotationId: string; versionNumber: number }) => {
      const response = await annotationsApi.confirmPlacement(input);
      return response.data;
    },
    onSuccess: () => {
      notify('Placement confirmed.', 'success');
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
    },
    onError: () => notify('Could not confirm the placement.', 'error'),
  });
}

/**
 * Gives a lost (ORPHANED/FAILED — or second-guessed MOVED) placement a new
 * home on the version's canvas (issue #457): the owner or the annotation's
 * author points at the new passage and the placement flips to PLACED with the
 * thread untouched.
 */
export function useReattachPlacement(notify: Notify) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { annotationId: string; versionNumber: number; anchor: Anchor }) => {
      const response = await annotationsApi.reattachPlacement({
        annotationId: input.annotationId,
        versionNumber: input.versionNumber,
        placementReattachRequest: { anchor: input.anchor },
      });
      return response.data;
    },
    onSuccess: () => {
      notify('Annotation re-attached.', 'success');
      queryClient.invalidateQueries({ queryKey: annotationKeys.all });
    },
    onError: () => notify('Could not re-attach the annotation.', 'error'),
  });
}
