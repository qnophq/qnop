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

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { NotificationType, type NotificationDetail } from '../../api/generated';
import { useWorkflowLabel } from '../reviews/useWorkflowLabel';

/** What the wording needs from a notification; both the inbox row and the detail carry it. */
type NotificationFields = Pick<
  NotificationDetail,
  | 'type'
  | 'title'
  | 'actorName'
  | 'documentTitle'
  | 'accessible'
  | 'decision'
  | 'fromState'
  | 'toState'
  | 'versionNumber'
  | 'actionTarget'
> &
  Partial<Pick<NotificationDetail, 'body' | 'actionLabel'>>;

/** The types whose headline or body names who did it. */
const ACTOR_LED = new Set<NotificationType>([
  NotificationType.Mention,
  NotificationType.ParticipantAdded,
  NotificationType.AnnotationCreated,
  NotificationType.AnnotationDecided,
  NotificationType.CommentAdded,
  NotificationType.VersionUploaded,
]);

const DECISIONS = ['resolved', 'reopened', 'dismissed'] as const;
type Decision = (typeof DECISIONS)[number];

function knownDecision(decision: string | null | undefined): Decision | null {
  return DECISIONS.find((known) => known === decision) ?? null;
}

/**
 * A notification's headline, body and action label in the active language (issue #836).
 *
 * <p>The server renders all three in English; it also ships the facts they are built from (the
 * decision, the workflow transition, the version, what the action opens), so the client words
 * them itself. A type or fact the client does not know keeps the server's English text — a
 * newer server never renders blank.
 *
 * <p>Names and titles are interpolated by plain `t()` and rendered as React text, so they are
 * never parsed as markup.
 */
export function useNotificationText() {
  const { t } = useTranslation('notifications');
  const workflowLabel = useWorkflowLabel();

  return useMemo(() => {
    const values = (n: NotificationFields) => ({
      actor: n.actorName ?? '',
      review: n.documentTitle ?? t('text.aReview'),
    });

    // The actor-led sentences need a name; without one, keep the server's text.
    const needsActor = (n: NotificationFields) => ACTOR_LED.has(n.type) && !n.actorName;

    const title = (n: NotificationFields): string => {
      if (n.type === NotificationType.ReviewDeleted) return t('text.title.reviewDeleted');
      if (!n.accessible) return t('text.title.tombstone');
      if (needsActor(n)) return n.title;
      const v = values(n);
      switch (n.type) {
        case NotificationType.Mention:
          return t('text.title.mention', v);
        case NotificationType.ParticipantAdded:
          return t('text.title.participantAdded');
        case NotificationType.AnnotationCreated:
          return t('text.title.annotationCreated', v);
        case NotificationType.AnnotationDecided: {
          const decision = knownDecision(n.decision);
          return decision ? t(`text.title.annotationDecided.${decision}`) : n.title;
        }
        case NotificationType.CommentAdded:
          return t('text.title.commentAdded', v);
        case NotificationType.VersionUploaded:
          return t('text.title.versionUploaded');
        case NotificationType.WorkflowChanged:
          return n.toState
            ? t('text.title.workflowChanged', { state: workflowLabel(n.toState) })
            : n.title;
        default:
          return n.title;
      }
    };

    const body = (n: NotificationFields): string => {
      const fallback = n.body ?? '';
      const v = values(n);
      if (n.type === NotificationType.ReviewDeleted) return t('text.body.reviewDeleted', v);
      if (!n.accessible) return t('text.body.tombstone');
      if (needsActor(n)) return fallback;
      switch (n.type) {
        case NotificationType.Mention:
          return t('text.body.mention', v);
        case NotificationType.ParticipantAdded:
          return t('text.body.participantAdded', v);
        case NotificationType.AnnotationCreated:
          return t('text.body.annotationCreated', v);
        case NotificationType.AnnotationDecided: {
          const decision = knownDecision(n.decision);
          return decision ? t(`text.body.annotationDecided.${decision}`, v) : fallback;
        }
        case NotificationType.CommentAdded:
          return t('text.body.commentAdded', v);
        case NotificationType.VersionUploaded:
          return n.versionNumber != null
            ? t('text.body.versionUploaded', { ...v, version: n.versionNumber })
            : fallback;
        case NotificationType.WorkflowChanged:
          return n.fromState && n.toState
            ? t('text.body.workflowChanged', {
                ...v,
                from: workflowLabel(n.fromState),
                to: workflowLabel(n.toState),
              })
            : fallback;
        default:
          return fallback;
      }
    };

    const actionLabel = (n: NotificationFields): string => {
      if (n.actionTarget === 'ANNOTATION') return t('text.action.openAnnotation');
      if (n.actionTarget === 'REVIEW') return t('text.action.openReview');
      return n.actionLabel ?? t('text.action.openReview');
    };

    return { title, body, actionLabel };
  }, [t, workflowLabel]);
}
