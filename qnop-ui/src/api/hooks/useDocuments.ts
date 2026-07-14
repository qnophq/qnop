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

import { useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  DocumentResponse,
  DocumentVersionListResponse,
  RenderedDocumentResponse,
} from '../generated';
import { ExtractionStatus } from '../generated';
import { axiosInstance, documentsApi } from '../config';

/** How often to re-poll the version list while extraction is still running. */
const EXTRACTION_POLL_MS = 2500;

export const documentKeys = {
  all: ['documents'] as const,
  detail: (documentId: string) => [...documentKeys.all, 'detail', documentId] as const,
  bySlug: (slug: string) => [...documentKeys.all, 'by-slug', slug] as const,
  versions: (documentId: string) => [...documentKeys.all, 'versions', documentId] as const,
  rendered: (documentId: string, versionNumber: number) =>
    [...documentKeys.all, 'rendered', documentId, versionNumber] as const,
  original: (documentId: string, versionNumber: number) =>
    [...documentKeys.all, 'original', documentId, versionNumber] as const,
};

/** A review document's metadata (title, owner, workflow state, latest version). */
export function useDocument(documentId: string) {
  return useQuery<DocumentResponse>({
    queryKey: documentKeys.detail(documentId),
    queryFn: async () => {
      const response = await documentsApi.getDocument({ documentId });
      return response.data;
    },
    // Callers may sit outside a resolved review (empty id) — never fetch then.
    enabled: documentId !== '',
  });
}

/**
 * Resolves a review by its human-readable slug (issue #411). Seeds the detail
 * cache with the response so the page's `useDocument` renders without a second
 * round-trip. Slugs are immutable, so a resolved mapping stays fresh.
 */
export function useDocumentBySlug(slug: string, enabled: boolean) {
  const queryClient = useQueryClient();
  return useQuery<DocumentResponse>({
    queryKey: documentKeys.bySlug(slug),
    queryFn: async () => {
      const response = await documentsApi.getDocumentBySlug({ slug });
      queryClient.setQueryData(documentKeys.detail(response.data.id), response.data);
      return response.data;
    },
    enabled,
    staleTime: Infinity,
  });
}

/**
 * The document's version list, oldest first. The list is re-polled while the
 * watched version's server-side extraction (ADR-0032) is still PENDING — and
 * also while the watched version is missing from the cached list entirely
 * (right after a new-version upload the list may still be stale, issue #300) —
 * so the viewer flips to annotatable as soon as the rendered representation is
 * READY, without a manual reload or a window-focus refetch.
 */
export function useDocumentVersions(documentId: string, watchVersion?: number) {
  return useQuery<DocumentVersionListResponse>({
    queryKey: documentKeys.versions(documentId),
    queryFn: async () => {
      const response = await documentsApi.listDocumentVersions({ documentId });
      return response.data;
    },
    refetchInterval: (query) => {
      if (!watchVersion) return false;
      const versions = query.state.data?.versions;
      const watched = versions?.find((v) => v.versionNumber === watchVersion);
      if (!watched) {
        // A watched version the cached list does not know yet means the list is
        // stale — poll until it appears. Bound this to the plausibly-next
        // version (one above the current max): an out-of-range version (a stale
        // or tampered `?version=` URL) never arrives, so it must not poll
        // forever.
        const maxKnown = versions?.reduce((max, v) => Math.max(max, v.versionNumber), 0) ?? 0;
        return watchVersion <= maxKnown + 1 ? EXTRACTION_POLL_MS : false;
      }
      return watched.extractionStatus === ExtractionStatus.Pending ? EXTRACTION_POLL_MS : false;
    },
  });
}

/**
 * The server-authoritative rendered representation (surfaces + text spans with
 * normalized boxes, ADR-0032). The endpoint answers 409 until extraction is
 * READY, so callers gate with `enabled` on the version's extraction status.
 */
export function useRenderedDocument(documentId: string, versionNumber: number, enabled: boolean) {
  return useQuery<RenderedDocumentResponse>({
    queryKey: documentKeys.rendered(documentId, versionNumber),
    queryFn: async () => {
      const response = await documentsApi.getRenderedDocument({ documentId, versionNumber });
      return response.data;
    },
    enabled: enabled && versionNumber >= 1,
    // The rendered representation of a version is immutable once READY.
    staleTime: Infinity,
  });
}

/**
 * The original binary of a version, served through the server with per-request
 * authorization (ADR-0032 §5). The endpoint is deliberately outside the
 * generated OpenAPI contract, hence the plain axios call; the bearer token is
 * attached by the shared instance's interceptor. Versions are immutable, so the
 * bytes never go stale.
 */
export function useOriginalPdf(documentId: string, versionNumber: number | undefined) {
  return useQuery<ArrayBuffer>({
    queryKey: documentKeys.original(documentId, versionNumber ?? 0),
    queryFn: async () => {
      const response = await axiosInstance.get<ArrayBuffer>(
        `/documents/${documentId}/versions/${versionNumber}/original`,
        { responseType: 'arraybuffer' },
      );
      return response.data;
    },
    enabled: versionNumber !== undefined && versionNumber >= 1,
    staleTime: Infinity,
  });
}
