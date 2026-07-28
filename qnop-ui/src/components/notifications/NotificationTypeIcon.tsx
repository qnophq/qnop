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

import {
  AtSign,
  CheckCircle2,
  FileUp,
  GitBranch,
  MessageSquare,
  MessageSquarePlus,
  UserPlus,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { NotificationType } from '../../api/generated';

/**
 * The glyph vocabulary of the inbox (issue #538) — one icon per notification
 * type, so a row's kind is readable before its text is.
 */
const ICONS: Record<NotificationType, LucideIcon> = {
  [NotificationType.Mention]: AtSign,
  [NotificationType.ParticipantAdded]: UserPlus,
  [NotificationType.AnnotationCreated]: MessageSquarePlus,
  [NotificationType.AnnotationDecided]: CheckCircle2,
  [NotificationType.CommentAdded]: MessageSquare,
  [NotificationType.VersionUploaded]: FileUp,
  [NotificationType.WorkflowChanged]: GitBranch,
};

export function NotificationTypeIcon({
  type,
  size = 18,
}: {
  type: NotificationType;
  size?: number;
}) {
  const Icon = ICONS[type] ?? MessageSquare;
  return <Icon size={size} aria-hidden />;
}
