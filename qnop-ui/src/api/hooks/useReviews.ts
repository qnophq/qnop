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

import { useEffect, useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  DocumentListResponse,
  DocumentResponse,
  ParticipantCreateRequest,
  ParticipantListResponse,
  PrincipalListResponse,
  WorkflowStatus,
} from '../generated';
import { axiosInstance, documentsApi, principalsApi, reviewWorkflowApi } from '../config';
import { documentKeys } from './useDocuments';

export interface ReviewListParams {
  q?: string;
  sort?: string;
  page: number;
  size: number;
}

export const reviewKeys = {
  all: ['reviews'] as const,
  list: (params: ReviewListParams) => [...reviewKeys.all, 'list', params] as const,
  participants: (documentId: string) => [...reviewKeys.all, 'participants', documentId] as const,
  workflow: (documentId: string) => [...reviewKeys.all, 'workflow', documentId] as const,
  principals: (q: string) => [...reviewKeys.all, 'principals', q] as const,
  teamMembers: (teamId: string) => [...reviewKeys.all, 'team-members', teamId] as const,
};

/** A page of the caller's reviews (owned or participating, incl. via team). */
export function useReviews(params: ReviewListParams) {
  return useQuery<DocumentListResponse>({
    queryKey: reviewKeys.list(params),
    queryFn: async () => {
      const response = await documentsApi.listDocuments({
        q: params.q || undefined,
        sort: params.sort,
        page: params.page,
        size: params.size,
      });
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}

/** The document's reviewers (users or teams) with display names. */
export function useParticipants(documentId: string, enabled = true) {
  return useQuery<ParticipantListResponse>({
    queryKey: reviewKeys.participants(documentId),
    queryFn: async () => {
      const response = await documentsApi.listParticipants({ documentId });
      return response.data;
    },
    enabled,
  });
}

export function useAddParticipant(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: ParticipantCreateRequest) => {
      const response = await documentsApi.addParticipant({
        documentId,
        participantCreateRequest: request,
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reviewKeys.participants(documentId) });
      queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

export function useRemoveParticipant(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (participantId: string) => {
      await documentsApi.removeParticipant({ documentId, participantId });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reviewKeys.participants(documentId) });
      queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

/** Directory search for the reviewer picker (enabled users and teams, names only). */
export function usePrincipalSearch(q: string) {
  return useQuery<PrincipalListResponse>({
    queryKey: reviewKeys.principals(q),
    queryFn: async () => {
      const response = await principalsApi.searchPrincipals({ q: q || undefined });
      return response.data;
    },
    placeholderData: keepPreviousData,
  });
}

/** The users behind a team principal — names only (issue #403). */
export function useTeamMembers(teamId: string, enabled: boolean) {
  return useQuery<PrincipalListResponse>({
    queryKey: reviewKeys.teamMembers(teamId),
    queryFn: async () => {
      const response = await principalsApi.listTeamMembers({ teamId });
      return response.data;
    },
    enabled,
  });
}

/** The document's workflow state and structurally reachable transitions. */
export function useWorkflow(documentId: string, enabled = true) {
  return useQuery<WorkflowStatus>({
    queryKey: reviewKeys.workflow(documentId),
    queryFn: async () => {
      const response = await reviewWorkflowApi.getDocumentWorkflow({ documentId });
      return response.data;
    },
    enabled,
  });
}

/**
 * Executes a workflow transition. The POST is authoritative — guards may veto
 * with 409 even when a transition is structurally offered (ADR-0011).
 */
export function useTransitionWorkflow(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (targetState: string) => {
      const response = await reviewWorkflowApi.transitionDocumentWorkflow({
        documentId,
        workflowTransitionRequest: { targetState },
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reviewKeys.workflow(documentId) });
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
      queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

export interface UploadResult {
  documentId: string;
  versionNumber: number;
  extractionStatus: string;
}

/**
 * Creates a new review from a first upload. The multipart upload endpoint is
 * deliberately outside the generated contract (ADR-0028), hence the plain
 * axios call; `onProgress` reports 0..1 for the wizard's progress bar.
 */
export function useCreateReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      title: string;
      file: File;
      dueAt?: string | null;
      onProgress?: (fraction: number) => void;
    }) => {
      const form = new FormData();
      form.append('title', input.title);
      form.append('file', input.file);
      if (input.dueAt) form.append('dueAt', input.dueAt);
      const response = await axiosInstance.post<UploadResult>('/documents', form, {
        onUploadProgress: (event) => {
          if (event.total) input.onProgress?.(event.loaded / event.total);
        },
      });
      return response.data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: reviewKeys.all }),
  });
}

/**
 * Sets or clears the optional review due date (owner-only, issue #295). A `null`
 * clears it — the PATCH body's `dueAt` is the desired state, so omitting the
 * field tells the server to clear the deadline.
 */
export function useUpdateDueDate(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (dueAt: string | null) => {
      const response = await documentsApi.updateDocument({
        documentId,
        documentUpdateRequest: { dueAt: dueAt ?? undefined },
      });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
      queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

/** Uploads a new version to an existing review (owner-only re-upload, ADR-0011). */
export function useUploadVersion(documentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { file: File; onProgress?: (fraction: number) => void }) => {
      const form = new FormData();
      form.append('file', input.file);
      const response = await axiosInstance.post<UploadResult>(
        `/documents/${documentId}/versions`,
        form,
        {
          onUploadProgress: (event) => {
            if (event.total) input.onProgress?.(event.loaded / event.total);
          },
        },
      );
      return response.data;
    },
    onSuccess: (result) => {
      // The upload response is authoritative about the new version number. Bump the
      // cached document detail synchronously so the review page immediately resolves
      // and watches the new version (its extraction polling then runs) instead of
      // falling back to the stale latestVersionNumber until the refetch below lands
      // (issue #300). The versions list itself converges via invalidation + polling.
      queryClient.setQueryData<DocumentResponse>(documentKeys.detail(documentId), (detail) =>
        detail
          ? {
              ...detail,
              latestVersionNumber: Math.max(detail.latestVersionNumber, result.versionNumber),
            }
          : detail,
      );
      queryClient.invalidateQueries({ queryKey: documentKeys.all });
      queryClient.invalidateQueries({ queryKey: reviewKeys.all });
    },
  });
}

/**
 * Stamps the caller's visit once per page mount and returns the PREVIOUS
 * visit (issue #307) — the session's unseen-marker baseline. The ref guard
 * keeps StrictMode's double effect from firing a second stamp, which would
 * instantly wipe the fresh markers. Best-effort: on failure the markers
 * simply stay off.
 */
export function useRecordVisit(documentId: string): string | null {
  const [previousSeenAt, setPreviousSeenAt] = useState<string | null>(null);
  const stampedFor = useRef<string | null>(null);

  useEffect(() => {
    if (!documentId || stampedFor.current === documentId) return;
    stampedFor.current = documentId;
    documentsApi.recordVisit({ documentId }).then(
      (response) => setPreviousSeenAt(response.data.previousSeenAt ?? null),
      () => undefined,
    );
  }, [documentId]);

  return previousSeenAt;
}
