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
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { AxiosError, type AxiosResponse } from 'axios';
import type {
  AnnotationView,
  ParticipantListResponse,
  WorkflowStatus,
} from '../../../api/generated';
import { AnnotationStatus, ParticipantKind, PlacementStatus } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { axiosInstance, documentsApi, reviewWorkflowApi } from '../../../api/config';
import { useAuthStore } from '../../../stores/authStore';
import { ReviewHubHead } from './ReviewHubHead';

vi.mock('../../../api/config', () => ({
  axiosInstance: { post: vi.fn(), get: vi.fn() },
  documentsApi: {
    listParticipants: vi.fn(),
    addParticipant: vi.fn(),
    removeParticipant: vi.fn(),
    updateDocument: vi.fn(),
  },
  principalsApi: { searchPrincipals: vi.fn() },
  reviewWorkflowApi: {
    getDocumentWorkflow: vi.fn(),
    transitionDocumentWorkflow: vi.fn(),
  },
  configApi: { getServerConfig: vi.fn() },
}));
vi.mock('../../../api/hooks/useConfig', () => ({
  useConfig: () => ({ data: { upload: { maxDocumentSizeMb: 50 } } }),
}));

const DOC_ID = '00000000-0000-0000-0000-0000000000d1';
const ME = '00000000-0000-0000-0000-0000000000aa';

function annotation(status: AnnotationStatus): AnnotationView {
  return {
    id: `a-${Math.random().toString(36).slice(2)}`,
    documentId: DOC_ID,
    authorId: ME,
    status,
    placementStatus: PlacementStatus.Placed,
    anchor: { region: { surfaceIndex: 0, box: { x: 0, y: 0, width: 0.1, height: 0.1 } } },
    commentCount: 0,
    reactions: [],
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
  };
}

const PARTICIPANTS: ParticipantListResponse = {
  participants: [
    {
      id: 'p1',
      kind: ParticipantKind.User,
      principalId: 'u-max',
      displayName: 'Max Member',
    },
  ],
};

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <ThemeProvider theme={buildTheme('light')}>
      {/* The user hover-card triggers render profile RouterLinks (issue #482). */}
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

const notify = vi.fn();
const onVersionUploaded = vi.fn();

function renderHub({
  isOwner = true,
  annotations = [] as AnnotationView[],
  dueAt = null as string | null,
  workflowState = 'IN_REVIEW',
} = {}) {
  return render(
    <ReviewHubHead
      documentId={DOC_ID}
      ownerId={isOwner ? ME : 'owner-far-away'}
      isOwner={isOwner}
      ownUserId={ME}
      anonymous={false}
      annotations={annotations}
      dueAt={dueAt}
      workflowState={workflowState}
      notify={notify}
      onVersionUploaded={onVersionUploaded}
    />,
    { wrapper },
  );
}

function mockWorkflow(status: WorkflowStatus) {
  vi.mocked(reviewWorkflowApi.getDocumentWorkflow).mockResolvedValue({
    data: status,
  } as Awaited<ReturnType<typeof reviewWorkflowApi.getDocumentWorkflow>>);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(documentsApi.listParticipants).mockResolvedValue({
    data: PARTICIPANTS,
  } as Awaited<ReturnType<typeof documentsApi.listParticipants>>);
  mockWorkflow({
    state: 'IN_REVIEW',
    allowedTransitions: ['FINALIZED', 'CANCELLED'],
    mayTransition: true,
    transitions: [
      { targetState: 'FINALIZED', available: true },
      { targetState: 'CANCELLED', available: true },
    ],
  });
});

describe('ReviewHubHead — owner', () => {
  it('shows the owner prominently, resolved as self by display name', () => {
    useAuthStore.setState({ userId: ME, displayName: 'Paula Owner' });
    renderHub();
    expect(screen.getByTestId('review-owner')).toHaveTextContent('Owner');
    expect(screen.getByTestId('review-owner')).toHaveTextContent('Paula Owner');
  });
});

describe('ReviewHubHead — progress & participants', () => {
  it('shows resolved/total progress from the annotations', async () => {
    renderHub({
      annotations: [
        annotation(AnnotationStatus.Open),
        annotation(AnnotationStatus.Resolved),
        annotation(AnnotationStatus.Resolved),
      ],
    });

    expect(
      screen.getByRole('progressbar', { name: '2 of 3 annotations resolved' }),
    ).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('1')).toBeInTheDocument());
  });

  it('opens the participants dialog from the avatar stack', async () => {
    renderHub();

    fireEvent.click(await screen.findByTestId('participants-button'));

    expect(await screen.findByText('Participants')).toBeInTheDocument();
    expect(await screen.findByText('Max Member')).toBeInTheDocument();
  });
});

describe('ReviewHubHead — workflow transitions', () => {
  it('confirms and executes a transition from the allowed set', async () => {
    const finalized: WorkflowStatus = {
      state: 'FINALIZED',
      allowedTransitions: [],
      mayTransition: true,
      transitions: [],
    };
    vi.mocked(reviewWorkflowApi.transitionDocumentWorkflow).mockResolvedValue({
      data: finalized,
    } as Awaited<ReturnType<typeof reviewWorkflowApi.transitionDocumentWorkflow>>);
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: /Change status/ }));
    fireEvent.click(await screen.findByText('Move to Finalized'));
    fireEvent.click(screen.getByRole('button', { name: 'Change status' }));

    await waitFor(() =>
      expect(reviewWorkflowApi.transitionDocumentWorkflow).toHaveBeenCalledWith({
        documentId: DOC_ID,
        workflowTransitionRequest: { targetState: 'FINALIZED' },
      }),
    );
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Review moved to Finalized.'));
  });

  // Issue #568: the capability is actor-scoped, and blocked targets explain
  // themselves instead of silently disappearing.
  it('hides the status button entirely from a caller without the capability', async () => {
    mockWorkflow({
      state: 'IN_REVIEW',
      allowedTransitions: ['FINALIZED', 'CANCELLED'],
      mayTransition: false,
      transitions: [
        { targetState: 'FINALIZED', available: true },
        { targetState: 'CANCELLED', available: true },
      ],
    });
    renderHub();

    await screen.findByTestId('participants-button');
    expect(screen.queryByRole('button', { name: /Change status/ })).not.toBeInTheDocument();
  });

  it('renders a blocked target disabled with its guard reason (#568)', async () => {
    mockWorkflow({
      state: 'IN_REVIEW',
      allowedTransitions: ['FINALIZED', 'CANCELLED'],
      mayTransition: true,
      transitions: [
        {
          targetState: 'FINALIZED',
          available: false,
          blockedReason: 'cannot finalize: 3 open annotation(s) must be resolved first',
        },
        { targetState: 'CANCELLED', available: true },
      ],
    });
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: /Change status/ }));

    const finalize = await screen.findByText('Move to Finalized');
    expect(finalize.closest('li')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Move to Cancelled').closest('li')).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  // Issue #568: cancelling settles open annotations automatically — the
  // confirm dialog must spell the consequence out.
  it('states the auto-close consequence when cancelling over open annotations', async () => {
    renderHub({
      annotations: [annotation(AnnotationStatus.Open), annotation(AnnotationStatus.Open)],
    });

    fireEvent.click(await screen.findByRole('button', { name: /Change status/ }));
    fireEvent.click(await screen.findByText('Move to Cancelled'));

    expect(
      screen.getByText(/2 open annotations will be closed automatically with a standard comment/),
    ).toBeInTheDocument();
  });

  it('omits the auto-close note for a finalize while the operator switch is off', async () => {
    renderHub({
      annotations: [annotation(AnnotationStatus.Open)],
    });

    fireEvent.click(await screen.findByRole('button', { name: /Change status/ }));
    fireEvent.click(await screen.findByText('Move to Finalized'));

    expect(screen.queryByText(/closed automatically/)).not.toBeInTheDocument();
  });

  it('surfaces a guard veto (409 INVALID_TRANSITION) as a mapped error toast', async () => {
    const veto = new AxiosError('conflict');
    veto.response = {
      status: 409,
      data: { code: 'INVALID_TRANSITION', message: 'server prose' },
    } as AxiosResponse;
    vi.mocked(reviewWorkflowApi.transitionDocumentWorkflow).mockRejectedValue(veto);
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: /Change status/ }));
    fireEvent.click(await screen.findByText('Move to Finalized'));
    fireEvent.click(screen.getByRole('button', { name: 'Change status' }));

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith(
        'This status change is not possible right now — open annotations or pending placements may remain.',
        'error',
      ),
    );
  });

  it('hides the status button when no transitions are offered', async () => {
    mockWorkflow({
      state: 'FINALIZED',
      allowedTransitions: [],
      mayTransition: true,
      transitions: [],
    });
    renderHub();

    await screen.findByTestId('participants-button');
    expect(screen.queryByRole('button', { name: /Change status/ })).not.toBeInTheDocument();
  });
});

describe('ReviewHubHead — new version upload', () => {
  it('uploads a PDF and reports the new version', async () => {
    vi.mocked(axiosInstance.post).mockResolvedValue({
      data: { documentId: DOC_ID, versionNumber: 3, extractionStatus: 'PENDING' },
    });
    renderHub();

    const file = new File(['%PDF-1.4'], 'v3.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByTestId('version-file-input'), { target: { files: [file] } });

    await waitFor(() => expect(onVersionUploaded).toHaveBeenCalledWith(3));
    expect(vi.mocked(axiosInstance.post).mock.calls[0][0]).toBe(`/documents/${DOC_ID}/versions`);
    expect(notify).toHaveBeenCalledWith('Version 3 uploaded.');
  });

  it('rejects a non-PDF without calling the API', async () => {
    renderHub();

    const file = new File(['x'], 'notes.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByTestId('version-file-input'), { target: { files: [file] } });

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Only PDF documents are supported.', 'error'),
    );
    expect(axiosInstance.post).not.toHaveBeenCalled();
  });

  it('hides the upload control for non-owners', async () => {
    renderHub({ isOwner: false });

    await screen.findByTestId('participants-button');
    expect(screen.queryByRole('button', { name: /New version/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId('version-file-input')).not.toBeInTheDocument();
  });
});

describe('ReviewHubHead — due date', () => {
  const DAY_MS = 24 * 60 * 60_000;

  it('lets the owner open the due-date editor from a "Set due date" affordance', () => {
    renderHub({ dueAt: null });

    fireEvent.click(screen.getByTestId('due-date-button'));

    expect(screen.getByRole('heading', { name: 'Review due date' })).toBeInTheDocument();
  });

  it('flags an overdue open review in the owner affordance', () => {
    renderHub({
      dueAt: new Date(Date.now() - 2 * DAY_MS - 60_000).toISOString(),
      workflowState: 'IN_REVIEW',
    });

    const label = screen.getByText('overdue by 2 days');
    expect(label).toHaveAttribute('data-overdue', 'true');
  });

  it('shows the due date read-only for non-owners, without an editor button', async () => {
    renderHub({
      isOwner: false,
      dueAt: new Date(Date.now() + 4 * DAY_MS + 60_000).toISOString(),
      workflowState: 'IN_REVIEW',
    });

    await screen.findByTestId('participants-button');
    expect(screen.getByText('due in 4 days')).toBeInTheDocument();
    expect(screen.queryByTestId('due-date-button')).not.toBeInTheDocument();
  });
});
