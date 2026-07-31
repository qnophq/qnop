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

import type { Theme } from '@mui/material/styles';
import { NotificationType } from '../../api/generated';

/**
 * The identity of a notification type (issue #538) — its name and its colour.
 *
 * Giving each type a stable colour is what turns a list of grey rows into
 * something recognisable: a mention always arrives blue, a decision always
 * green, a new version always amber. The detail page then wears that colour as
 * a crest, so "what happened" is legible before a single word is read. Colours
 * come from the brand's avatar ramp, so nothing new enters the palette.
 */
const LABELS: Record<NotificationType, string> = {
  [NotificationType.Mention]: 'Mention',
  [NotificationType.ParticipantAdded]: 'Invitation',
  [NotificationType.AnnotationCreated]: 'New annotation',
  [NotificationType.AnnotationDecided]: 'Decision',
  [NotificationType.CommentAdded]: 'Reply',
  [NotificationType.VersionUploaded]: 'New version',
  [NotificationType.WorkflowChanged]: 'Workflow',
  [NotificationType.ReviewDeleted]: 'Review deleted',
};

/** Index into the brand's avatar ramp — see `tokens.ts`. */
const TONE_INDEX: Record<NotificationType, number> = {
  [NotificationType.Mention]: 0, // signature blue — being named is the loudest thing
  [NotificationType.ParticipantAdded]: 5, // violet — you joined something
  [NotificationType.AnnotationCreated]: 1, // deep blue — work arriving
  [NotificationType.AnnotationDecided]: 2, // green — something got settled
  [NotificationType.CommentAdded]: 6, // mid blue — the conversation continues
  [NotificationType.VersionUploaded]: 3, // amber — the document itself moved
  [NotificationType.WorkflowChanged]: 7, // slate — a state change, not a person
  // Red, the only one: everything else in this inbox reports work moving along,
  // and this reports work that is gone (issue #421).
  [NotificationType.ReviewDeleted]: 4,
};

export function notificationLabel(type: NotificationType): string {
  return LABELS[type] ?? 'Notification';
}

/** The type's colour, resolved against the current theme's avatar ramp. */
export function notificationTone(type: NotificationType, theme: Theme): string {
  const ramp = theme.qnop.avatarPalette;
  return ramp[TONE_INDEX[type] ?? 0] ?? theme.palette.primary.main;
}
