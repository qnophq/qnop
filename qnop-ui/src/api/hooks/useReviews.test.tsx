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

import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type {
  DocumentListResponse,
  ParticipantListResponse,
  PrincipalListResponse,
  WorkflowStatus,
} from '../generated';
import {
  reviewKeys,
  useAddParticipant,
  useCreateReview,
  useParticipants,
  usePrincipalSearch,
  useReviews,
  useTransitionWorkflow,
  useUploadVersion,
  useWorkflow,
} from './useReviews';
import { documentKeys } from './useDocuments';
import { axiosInstance, documentsApi, principalsApi, reviewWorkflowApi } from '../config';

vi.mock('../config', () => ({
  documentsApi: {
    listDocuments: vi.fn(),
    listParticipants: vi.fn(),
    addParticipant: vi.fn(),
    removeParticipant: vi.fn(),
  },
  principalsApi: {
    searchPrincipals: vi.fn(),
  },
  reviewWorkflowApi: {
    getDocumentWorkflow: vi.fn(),
    transitionDocumentWorkflow: vi.fn(),
  },
  axiosInstance: {
    post: vi.fn(),
  },
}));

const DOC_ID = '3f6f6f6f-0000-0000-0000-000000000001';

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('reviewKeys', () => {
  it('namespaces per concern', () => {
    expect(reviewKeys.participants(DOC_ID)).toEqual(['reviews', 'participants', DOC_ID]);
    expect(reviewKeys.workflow(DOC_ID)).toEqual(['reviews', 'workflow', DOC_ID]);
  });
});

describe('useReviews', () => {
  it('forwards query, sort and pagination', async () => {
    const empty: DocumentListResponse = { items: [], total: 0, page: 0, size: 20 };
    vi.mocked(documentsApi.listDocuments).mockResolvedValue({ data: empty } as Awaited<
      ReturnType<typeof documentsApi.listDocuments>
    >);

    const { result } = renderHook(
      () => useReviews({ q: 'nda', sort: 'title,asc', page: 1, size: 10 }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(documentsApi.listDocuments).toHaveBeenCalledWith({
      q: 'nda',
      sort: 'title,asc',
      page: 1,
      size: 10,
    });
  });
});

describe('useParticipants', () => {
  it('fetches the reviewer set', async () => {
    const empty: ParticipantListResponse = { participants: [] };
    vi.mocked(documentsApi.listParticipants).mockResolvedValue({ data: empty } as Awaited<
      ReturnType<typeof documentsApi.listParticipants>
    >);

    const { result } = renderHook(() => useParticipants(DOC_ID), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(documentsApi.listParticipants).toHaveBeenCalledWith({ documentId: DOC_ID });
  });
});

describe('useAddParticipant', () => {
  it('posts the principal', async () => {
    vi.mocked(documentsApi.addParticipant).mockResolvedValue({ data: { id: 'p1' } } as Awaited<
      ReturnType<typeof documentsApi.addParticipant>
    >);

    const { result } = renderHook(() => useAddParticipant(DOC_ID), { wrapper });
    await result.current.mutateAsync({ userId: 'u9' });

    expect(documentsApi.addParticipant).toHaveBeenCalledWith({
      documentId: DOC_ID,
      participantCreateRequest: { userId: 'u9' },
    });
  });
});

describe('usePrincipalSearch', () => {
  it('searches the directory', async () => {
    const empty: PrincipalListResponse = { principals: [] };
    vi.mocked(principalsApi.searchPrincipals).mockResolvedValue({ data: empty } as Awaited<
      ReturnType<typeof principalsApi.searchPrincipals>
    >);

    const { result } = renderHook(() => usePrincipalSearch('al'), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(principalsApi.searchPrincipals).toHaveBeenCalledWith({ q: 'al' });
  });
});

describe('useWorkflow / useTransitionWorkflow', () => {
  it('reads the state and posts a transition', async () => {
    const draft: WorkflowStatus = { state: 'DRAFT', allowedTransitions: ['IN_REVIEW'] };
    const inReview: WorkflowStatus = { state: 'IN_REVIEW', allowedTransitions: [] };
    vi.mocked(reviewWorkflowApi.getDocumentWorkflow).mockResolvedValue({ data: draft } as Awaited<
      ReturnType<typeof reviewWorkflowApi.getDocumentWorkflow>
    >);
    vi.mocked(reviewWorkflowApi.transitionDocumentWorkflow).mockResolvedValue({
      data: inReview,
    } as Awaited<ReturnType<typeof reviewWorkflowApi.transitionDocumentWorkflow>>);

    const workflow = renderHook(() => useWorkflow(DOC_ID), { wrapper });
    await waitFor(() => expect(workflow.result.current.isSuccess).toBe(true));
    expect(workflow.result.current.data?.allowedTransitions).toEqual(['IN_REVIEW']);

    const transition = renderHook(() => useTransitionWorkflow(DOC_ID), { wrapper });
    await transition.result.current.mutateAsync('IN_REVIEW');
    expect(reviewWorkflowApi.transitionDocumentWorkflow).toHaveBeenCalledWith({
      documentId: DOC_ID,
      workflowTransitionRequest: { targetState: 'IN_REVIEW' },
    });
  });
});

describe('uploads', () => {
  it('creates a review via multipart with title and file', async () => {
    vi.mocked(axiosInstance.post).mockResolvedValue({
      data: { documentId: DOC_ID, versionNumber: 1, extractionStatus: 'PENDING' },
    });

    const { result } = renderHook(() => useCreateReview(), { wrapper });
    const file = new File(['%PDF'], 'contract.pdf', { type: 'application/pdf' });
    const created = await result.current.mutateAsync({ title: 'NDA', file });

    expect(created.documentId).toBe(DOC_ID);
    const [url, form] = vi.mocked(axiosInstance.post).mock.calls[0];
    expect(url).toBe('/documents');
    expect((form as FormData).get('title')).toBe('NDA');
    expect((form as FormData).get('file')).toBe(file);
  });

  it('uploads a new version to the document', async () => {
    vi.mocked(axiosInstance.post).mockResolvedValue({
      data: { documentId: DOC_ID, versionNumber: 2, extractionStatus: 'PENDING' },
    });

    const { result } = renderHook(() => useUploadVersion(DOC_ID), { wrapper });
    const file = new File(['%PDF'], 'v2.pdf', { type: 'application/pdf' });
    await result.current.mutateAsync({ file });

    expect(vi.mocked(axiosInstance.post).mock.calls[0][0]).toBe(`/documents/${DOC_ID}/versions`);
  });

  // Regression for issue #300: the review page derives the effective version from
  // detail.latestVersionNumber; a stale value right after the upload made the page
  // watch the OLD version (polling off) and stick on "Processing document…". The
  // upload response is authoritative, so the cache must be bumped synchronously.
  it('bumps the cached latestVersionNumber from the upload response', async () => {
    vi.mocked(axiosInstance.post).mockResolvedValue({
      data: { documentId: DOC_ID, versionNumber: 2, extractionStatus: 'PENDING' },
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(documentKeys.detail(DOC_ID), {
      id: DOC_ID,
      title: 'Contract',
      latestVersionNumber: 1,
    });
    const clientWrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useUploadVersion(DOC_ID), { wrapper: clientWrapper });
    await result.current.mutateAsync({ file: new File(['%PDF'], 'v2.pdf') });

    expect(
      (queryClient.getQueryData(documentKeys.detail(DOC_ID)) as { latestVersionNumber: number })
        .latestVersionNumber,
    ).toBe(2);
  });
});
