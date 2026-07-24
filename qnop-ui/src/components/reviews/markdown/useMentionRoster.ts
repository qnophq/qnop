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
import { ParticipantKind } from '../../../api/generated';
import { useDocument } from '../../../api/hooks/useDocuments';
import { useParticipants } from '../../../api/hooks/useReviews';
import type { MentionCandidate } from './mentionToken';

/**
 * The @-mention roster of a review (issue #462): its USER participants, keyed
 * by the real principal id. Empty in anonymous reviews, which disables the
 * picker so no real identity is offered where it is hidden (the server
 * resolves no mentions there either). Every composer shares this one hook so
 * annotations and comments cannot drift apart; both queries are cache-shared
 * with the surfaces that already load them.
 */
export function useMentionRoster(documentId: string | null | undefined): MentionCandidate[] {
  const review = useDocument(documentId ?? '').data;
  const participantsQuery = useParticipants(documentId ?? '', Boolean(documentId));
  return useMemo(() => {
    if (!review || review.anonymous) return [];
    return (participantsQuery.data?.participants ?? [])
      .filter((participant) => participant.kind === ParticipantKind.User)
      .map((participant) => ({ id: participant.principalId, name: participant.displayName }));
  }, [participantsQuery.data, review]);
}
