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

import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { LayoutGrid, List as ListIcon, Search } from 'lucide-react';
import { useAnnotations } from '../../api/hooks/useAnnotations';
import { useDocument, useDocumentVersions } from '../../api/hooks/useDocuments';
import { useParticipants, useRecordVisit } from '../../api/hooks/useReviews';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { ReviewViewTabs } from '../../components/reviews/hub/ReviewViewTabs';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { useToast } from '../../components/admin/layout/useToast';
import {
  mayResolveAnnotation,
  useResolveWithFeedback,
} from '../../components/reviews/panel/resolve';
import { TaskBoard } from '../../components/reviews/tasks/TaskBoard';
import { TaskDrawer } from '../../components/reviews/tasks/TaskDrawer';
import { TaskListRows } from '../../components/reviews/tasks/TaskListRows';
import type { TaskFilter } from '../../components/reviews/tasks/tasksModel';
import {
  columnOf,
  matchesQuery,
  parseTaskFilter,
  taskKeys,
} from '../../components/reviews/tasks/tasksModel';
import { useTasksViewMode } from '../../components/reviews/tasks/useTasksViewMode';
import { isOpenWorkflowState } from '../../components/reviews/workflowMeta';
import { ExtractionStatus } from '../../api/generated';
import { useAuthStore } from '../../stores/authStore';

const FILTERS: { key: TaskFilter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'open', label: 'Open' },
  { key: 'discussion', label: 'In discussion' },
  { key: 'done', label: 'Resolved' },
];

/**
 * The review's tasks workspace (issue #393, prototype `reviewhub.jsx`):
 * every annotation as an issue-tracker task on a kanban board or in a dense
 * list. The status filter and search live in the URL (shareable); the
 * board/list choice is a persisted personal preference. Dropping one's own
 * card on Resolved resolves it (issue #405); everything else runs through the
 * task drawer, which reuses the panel's thread and resolve pieces.
 */
export function ReviewTasksPage() {
  const { documentId = '' } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { toast, notify, clear } = useToast();
  const userId = useAuthStore((state) => state.userId);
  const ownDisplayName = useAuthStore((state) => state.displayName);

  const documentQuery = useDocument(documentId);
  const versionsQuery = useDocumentVersions(documentId);
  const participantsQuery = useParticipants(documentId);

  const document = documentQuery.data;
  const latestVersion = Math.max(
    document?.latestVersionNumber ?? 0,
    ...(versionsQuery.data?.versions.map((version) => version.versionNumber) ?? [0]),
  );
  const annotationsQuery = useAnnotations(
    documentId,
    latestVersion >= 1 ? latestVersion : undefined,
  );
  const annotations = annotationsQuery.data?.annotations ?? [];

  const [view, setView] = useTasksViewMode();
  // The unseen-marker baseline (issue #307): the PREVIOUS visit, stamped once.
  const previousSeenAt = useRecordVisit(documentId);
  const filter = parseTaskFilter(searchParams.get('filter'));
  const query = searchParams.get('q') ?? '';
  const [openTaskId, setOpenTaskId] = useState<string | null>(null);

  const { resolveWith } = useResolveWithFeedback(notify);

  const setParam = (key: 'filter' | 'q', value: string | null) => {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === '' || value === 'all') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    setSearchParams(next, { replace: true });
  };

  const participants = participantsQuery.data?.participants ?? [];
  // The panel's naming rule: self by display name; reviewers through the
  // participant directory; the owner stays structural on the document (never
  // a participant row), so a foreign owner reads as a plain participant.
  const authorNameOf = (authorId: string) =>
    authorId === userId
      ? (ownDisplayName ?? 'You')
      : (participants.find((participant) => participant.principalId === authorId)?.displayName ??
        'Participant');

  const countOf = (key: TaskFilter) =>
    key === 'all'
      ? annotations.length
      : annotations.filter((annotation) => columnOf(annotation) === key).length;

  const visible = annotations
    .filter((annotation) => filter === 'all' || columnOf(annotation) === filter)
    .filter((annotation) => matchesQuery(annotation, authorNameOf(annotation.authorId), query));

  const openTask = annotations.find((annotation) => annotation.id === openTaskId) ?? null;
  const keyByAnnotation = taskKeys(annotations);
  const taskKeyOf = (annotationId: string) => keyByAnnotation.get(annotationId) ?? '';

  const mayResolve = (annotation: (typeof annotations)[number]) =>
    mayResolveAnnotation(annotation, userId);

  const resolveByDrop = (annotationId: string) => {
    const annotation = annotations.find((candidate) => candidate.id === annotationId);
    if (!annotation || !mayResolve(annotation)) return;
    resolveWith(annotation);
  };

  const showInDocument = (annotationId: string) => {
    void navigate(`/reviews/${documentId}?annotation=${annotationId}`);
  };

  if (documentQuery.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 8 }}>
        <CircularProgress size={24} />
      </Stack>
    );
  }
  if (documentQuery.isError || !document) {
    return (
      <PageHeader
        title="This review is not available"
        titleAdornment={<Chip size="small" variant="outlined" label="Tasks" />}
      />
    );
  }

  return (
    <Stack spacing={2.5} sx={{ height: { md: '100%' }, minHeight: { md: 480 } }}>
      <PageHeader
        title={document.title}
        titleAdornment={<Chip size="small" variant="outlined" label="Tasks" />}
      />
      <ReviewViewTabs
        documentId={documentId}
        active="tasks"
        openTaskCount={annotations.filter((annotation) => columnOf(annotation) !== 'done').length}
        compareEnabled={
          (versionsQuery.data?.versions.filter(
            (version) => version.extractionStatus === ExtractionStatus.Ready,
          ).length ?? 0) >= 2
        }
      />

      {/* sub toolbar: view segment · status filter chips · search */}
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={view}
          onChange={(_event, next: 'board' | 'list' | null) => next && setView(next)}
          aria-label="Tasks presentation"
        >
          <ToggleButton value="board" aria-label="Board">
            <LayoutGrid size={14} style={{ marginRight: 6 }} /> Board
          </ToggleButton>
          <ToggleButton value="list" aria-label="List">
            <ListIcon size={14} style={{ marginRight: 6 }} /> List
          </ToggleButton>
        </ToggleButtonGroup>
        <Divider orientation="vertical" flexItem sx={{ my: 0.5 }} />
        {FILTERS.map(({ key, label }) => (
          <Chip
            key={key}
            size="small"
            label={`${label} ${countOf(key)}`}
            color={filter === key ? 'primary' : 'default'}
            variant={filter === key ? 'filled' : 'outlined'}
            onClick={() => setParam('filter', key)}
            data-testid={`task-filter-${key}`}
          />
        ))}
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          placeholder="Search tasks…"
          value={query}
          onChange={(event) => setParam('q', event.target.value)}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search size={14} />
                </InputAdornment>
              ),
              'aria-label': 'Search tasks',
            },
          }}
          sx={{ width: 220 }}
        />
      </Stack>

      {annotationsQuery.isPending ? (
        <Stack sx={{ alignItems: 'center', py: 6 }}>
          <CircularProgress size={22} />
        </Stack>
      ) : view === 'board' ? (
        <TaskBoard
          annotations={visible}
          previousSeenAt={previousSeenAt}
          taskKeyOf={taskKeyOf}
          authorNameOf={authorNameOf}
          mayResolve={mayResolve}
          onOpen={setOpenTaskId}
          onResolve={resolveByDrop}
        />
      ) : (
        <TaskListRows
          annotations={visible}
          taskKeyOf={taskKeyOf}
          authorNameOf={authorNameOf}
          onOpen={setOpenTaskId}
        />
      )}

      <TaskDrawer
        annotation={openTask}
        previousSeenAt={previousSeenAt}
        taskKey={openTask ? taskKeyOf(openTask.id) : ''}
        authorName={openTask ? authorNameOf(openTask.authorId) : ''}
        notify={notify}
        reviewClosed={!isOpenWorkflowState(document.workflowState)}
        onClose={() => setOpenTaskId(null)}
        onShowInDocument={showInDocument}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
