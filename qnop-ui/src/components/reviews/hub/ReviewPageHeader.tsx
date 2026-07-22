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

import type { AnnotationView, DocumentResponse } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { PageHeader } from '../../admin/layout/PageHeader';
import type { Notify } from '../../admin/layout/useToast';
import { useAuthStore } from '../../../stores/authStore';
import { AnonymousBadge } from '../AnonymousBadge';
import { WorkflowMilestones } from '../WorkflowMilestones';
import { useReviewDocumentId } from '../reviewDocumentId';
import { ReviewHubHead } from './ReviewHubHead';

interface ReviewPageHeaderProps {
  /** The review's document detail — carries every header-relevant field. */
  document: DocumentResponse;
  /** The page's annotation set, feeding the milestone counts and the hub head. */
  annotations: AnnotationView[];
  /** The page's toast seam. */
  notify: Notify;
  /** Where to go after a successful new-version upload — the page decides. */
  onVersionUploaded: (versionNumber: number) => void;
}

/**
 * THE review page header (issue #571): title, the workflow milestone path
 * (#568) with the anonymity badge, and the full hub head — identical on the
 * Document, Tasks and Compare tabs, so switching tabs never makes the top of
 * the page jump. The canonical document id and the viewer's identity are read
 * here (not props) so `isOwner` cannot drift between tabs; tab identity is the
 * tab bar's job, so this header deliberately has no per-tab slot.
 */
export function ReviewPageHeader({
  document,
  annotations,
  notify,
  onVersionUploaded,
}: ReviewPageHeaderProps) {
  const documentId = useReviewDocumentId();
  const userId = useAuthStore((s) => s.userId);

  return (
    <PageHeader
      title={document.title}
      titleAdornment={
        <>
          <WorkflowMilestones
            state={document.workflowState}
            total={annotations.length}
            resolved={annotations.filter((a) => a.status !== AnnotationStatus.Open).length}
          />
          {document.anonymous && <AnonymousBadge />}
        </>
      }
      action={
        <ReviewHubHead
          documentId={documentId}
          ownerId={document.ownerId}
          ownerSlug={document.ownerSlug}
          ownerDisplayName={document.ownerDisplayName}
          isOwner={document.ownerId === userId}
          ownUserId={userId}
          anonymous={document.anonymous ?? false}
          annotations={annotations}
          dueAt={document.dueAt ?? null}
          workflowState={document.workflowState}
          notify={notify}
          onVersionUploaded={onVersionUploaded}
        />
      }
    />
  );
}
