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
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { LayoutGrid, List as ListIcon, Plus } from 'lucide-react';
import { useAnnotations } from '../../api/hooks/useAnnotations';
import { useDocument, useDocumentVersions } from '../../api/hooks/useDocuments';
import { useRecordVisit } from '../../api/hooks/useReviews';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { ReviewViewTabs } from '../../components/reviews/hub/ReviewViewTabs';
import { useReviewDocumentId } from '../../components/reviews/reviewDocumentId';
import { useReviewPermalink } from '../../components/reviews/useReviewPermalink';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { useToast } from '../../components/admin/layout/useToast';
import type { FilterAuthor } from '../../components/reviews/panel/PanelFilterBar';
import { PanelFilterBar } from '../../components/reviews/panel/PanelFilterBar';
import type { AnnotationFilters } from '../../components/reviews/panel/panelFilters';
import { matchesFilters } from '../../components/reviews/panel/panelFilters';
import {
  mayResolveAnnotation,
  useResolveWithFeedback,
} from '../../components/reviews/panel/resolve';
import { NewTaskDialog } from '../../components/reviews/tasks/NewTaskDialog';
import { TaskBoard } from '../../components/reviews/tasks/TaskBoard';
import { TaskDrawer } from '../../components/reviews/tasks/TaskDrawer';
import { TaskListRows } from '../../components/reviews/tasks/TaskListRows';
import type { TaskFilter } from '../../components/reviews/tasks/tasksModel';
import { columnOf, parseTaskFilter, taskKeys } from '../../components/reviews/tasks/tasksModel';
import { useTasksViewMode } from '../../components/reviews/tasks/useTasksViewMode';
import { isOpenWorkflowState } from '../../components/reviews/workflowMeta';
import { AnnotationPriority, AnnotationType, ExtractionStatus } from '../../api/generated';
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
  // The raw segment may be a slug (issue #411) — sibling links keep it, while
  // all data access below uses the canonical id resolved by the route gate.
  const { documentId: routeSegment = '' } = useParams();
  const documentId = useReviewDocumentId();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { toast, notify, clear } = useToast();
  // Permalinks from the tasks drawer open the document in the recipient's own
  // view preference — the sharer is on the tasks surface, not panel/focus (#412).
  const buildPermalink = useReviewPermalink();
  const userId = useAuthStore((state) => state.userId);
  const ownDisplayName = useAuthStore((state) => state.displayName);

  const documentQuery = useDocument(documentId);
  const versionsQuery = useDocumentVersions(documentId);

  const document = documentQuery.data;
  const anonymous = document?.anonymous ?? false;
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
  // The facet filters share the panel's model (#403) and live in the URL like
  // everything else on this page.
  const typeParam = searchParams.get('type');
  const priorityParam = searchParams.get('priority');
  const facets: AnnotationFilters = {
    status: 'all',
    type: Object.values(AnnotationType).includes(typeParam as AnnotationType)
      ? (typeParam as AnnotationType)
      : null,
    priority: Object.values(AnnotationPriority).includes(priorityParam as AnnotationPriority)
      ? (priorityParam as AnnotationPriority)
      : null,
    author: searchParams.get('author'),
    query: searchParams.get('q') ?? '',
  };
  const [openTaskId, setOpenTaskId] = useState<string | null>(null);
  const [newTaskOpen, setNewTaskOpen] = useState(false);

  const { resolveWith } = useResolveWithFeedback(notify);

  const setParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === '' || value === 'all') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    setSearchParams(next, { replace: true });
  };

  const setFacets = (nextFacets: AnnotationFilters) => {
    const next = new URLSearchParams(searchParams);
    const write = (key: string, value: string | null) => {
      if (value === null || value === '') next.delete(key);
      else next.set(key, value);
    };
    write('type', nextFacets.type);
    write('priority', nextFacets.priority);
    write('author', nextFacets.author);
    write('q', nextFacets.query);
    setSearchParams(next, { replace: true });
  };

  // Author names are resolved server-side and ride on the annotation itself
  // (issue #413), honouring per-review anonymity — own contributions read
  // "You"; everyone else is the real name or a stable "Participant N" pseudonym.
  const authorNameById = new Map(
    annotations.map((annotation) => [
      annotation.authorId,
      annotation.authorDisplayName ?? 'Participant',
    ]),
  );
  const authorNameOf = (authorId: string) =>
    authorId === userId
      ? (ownDisplayName ?? 'You')
      : (authorNameById.get(authorId) ?? 'Participant');

  const countOf = (key: TaskFilter) =>
    key === 'all'
      ? annotations.length
      : annotations.filter((annotation) => columnOf(annotation) === key).length;

  // No author facet in an anonymous review (issue #413).
  const authors: FilterAuthor[] = anonymous
    ? []
    : [...new Set(annotations.map((annotation) => annotation.authorId))].map((id) => ({
        id,
        name: authorNameOf(id),
      }));

  const visible = annotations
    .filter((annotation) => filter === 'all' || columnOf(annotation) === filter)
    .filter((annotation) => matchesFilters(annotation, facets, authorNameOf(annotation.authorId)));

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
    void navigate(`/reviews/${routeSegment}?annotation=${annotationId}`);
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
          // A document-scoped task (issue #395) needs no selection — offered while the review is
          // open and has a version to author against; the server refuses a closed review anyway.
          isOpenWorkflowState(document.workflowState) && latestVersion >= 1 ? (
            <Button
              variant="contained"
              size="small"
              startIcon={<Plus size={16} />}
              onClick={() => setNewTaskOpen(true)}
            >
              New task
            </Button>
          ) : undefined
        }
      />
      <ReviewViewTabs
        documentId={routeSegment}
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
        <Box sx={{ width: { xs: '100%', sm: 560, md: 720 } }}>
          <PanelFilterBar
            filters={facets}
            onChange={setFacets}
            authors={authors}
            statusFacet={false}
            authorFacet={!anonymous}
            searchLabel="Search tasks"
          />
        </Box>
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
        threadParticipation={document.threadParticipation ?? 'OPEN'}
        ownerId={document.ownerId}
        onClose={() => setOpenTaskId(null)}
        onShowInDocument={showInDocument}
        buildPermalink={buildPermalink}
      />
      <NewTaskDialog
        open={newTaskOpen}
        documentId={documentId}
        versionNumber={latestVersion}
        notify={notify}
        onClose={() => setNewTaskOpen(false)}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
