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

import { useQuery } from '@tanstack/react-query';
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
  });
}

/**
 * The document's version list, oldest first. While the given version's server-side
 * extraction (ADR-0032) is still PENDING the list is re-polled, so the viewer
 * flips to annotatable as soon as the rendered representation is READY.
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
      const watched = query.state.data?.versions.find((v) => v.versionNumber === watchVersion);
      return watched?.extractionStatus === ExtractionStatus.Pending ? EXTRACTION_POLL_MS : false;
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
