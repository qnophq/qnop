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
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { ArrowLeft, LayoutGrid, List as ListIcon, Search } from 'lucide-react';
import { useAnnotations } from '../../api/hooks/useAnnotations';
import { useDocument, useDocumentVersions } from '../../api/hooks/useDocuments';
import { useParticipants } from '../../api/hooks/useReviews';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { useToast } from '../../components/admin/layout/useToast';
import {
  mayDecideAnnotation,
  useDecideWithFeedback,
} from '../../components/reviews/panel/decisions';
import { TaskBoard } from '../../components/reviews/tasks/TaskBoard';
import { TaskDrawer } from '../../components/reviews/tasks/TaskDrawer';
import { TaskListRows } from '../../components/reviews/tasks/TaskListRows';
import type { TaskFilter } from '../../components/reviews/tasks/tasksModel';
import { columnOf, matchesQuery, parseTaskFilter } from '../../components/reviews/tasks/tasksModel';
import { useTasksViewMode } from '../../components/reviews/tasks/useTasksViewMode';
import { AnnotationDecision } from '../../api/generated';
import { useAuthStore } from '../../stores/authStore';

const FILTERS: { key: TaskFilter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'open', label: 'Open' },
  { key: 'discussion', label: 'In discussion' },
  { key: 'done', label: 'Done' },
];

/**
 * The review's tasks workspace (issue #393, prototype `reviewhub.jsx`):
 * every annotation as an issue-tracker task on a kanban board or in a dense
 * list. The status filter and search live in the URL (shareable); the
 * board/list choice is a persisted personal preference. Dropping a card on
 * Done accepts it; everything else runs through the task drawer, which reuses
 * the panel's thread and decision pieces.
 */
export function ReviewTasksPage() {
  const { documentId = '' } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { toast, notify, clear } = useToast();
  const userId = useAuthStore((state) => state.userId);

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
  const filter = parseTaskFilter(searchParams.get('filter'));
  const query = searchParams.get('q') ?? '';
  const [openTaskId, setOpenTaskId] = useState<string | null>(null);

  const { decideWith } = useDecideWithFeedback(notify);

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
  const authorNameOf = (authorId: string) =>
    participants.find((participant) => participant.principalId === authorId)?.displayName ??
    'Participant';

  const countOf = (key: TaskFilter) =>
    key === 'all'
      ? annotations.length
      : annotations.filter((annotation) => columnOf(annotation) === key).length;

  const visible = annotations
    .filter((annotation) => filter === 'all' || columnOf(annotation) === filter)
    .filter((annotation) => matchesQuery(annotation, authorNameOf(annotation.authorId), query));

  const openTask = annotations.find((annotation) => annotation.id === openTaskId) ?? null;

  const mayDecide = (annotation: (typeof annotations)[number]) =>
    mayDecideAnnotation(annotation, userId, document?.ownerId ?? null);

  const acceptByDrop = (annotationId: string) => {
    const annotation = annotations.find((candidate) => candidate.id === annotationId);
    if (!annotation || !mayDecide(annotation)) return;
    decideWith(annotation, AnnotationDecision.Accepted);
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
        action={
          <Button
            component={RouterLink}
            to={`/reviews/${documentId}`}
            variant="outlined"
            size="small"
            startIcon={<ArrowLeft size={15} />}
          >
            Back to review
          </Button>
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
        <Box sx={{ width: 1, height: 22, bgcolor: 'divider' }} />
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
          authorNameOf={authorNameOf}
          mayDecide={mayDecide}
          onOpen={setOpenTaskId}
          onAccept={acceptByDrop}
        />
      ) : (
        <TaskListRows annotations={visible} authorNameOf={authorNameOf} onOpen={setOpenTaskId} />
      )}

      <TaskDrawer
        annotation={openTask}
        authorName={openTask ? authorNameOf(openTask.authorId) : ''}
        ownerId={document.ownerId}
        notify={notify}
        onClose={() => setOpenTaskId(null)}
        onShowInDocument={showInDocument}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
