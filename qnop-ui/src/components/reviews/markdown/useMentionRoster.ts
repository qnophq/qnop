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
import { useAuthStore } from '../../../stores/authStore';
import { useMentionContributors } from '../../../extensions/mentions';
import type { MentionCandidate } from './mentionToken';

/**
 * The @-mention roster of a review (issue #462): its USER participants, keyed
 * by the real principal id. Empty in anonymous reviews, which disables the
 * picker so no real identity is offered where it is hidden (the server
 * resolves no mentions there either). Every composer shares this one hook so
 * annotations and comments cannot drift apart; both queries are cache-shared
 * with the surfaces that already load them.
 *
 * <p>Registered mention contributors (issue #598) append their candidates —
 * e.g. a team the composer may ping — after the user roster; anonymity
 * disables them along with everything else.
 */
export function useMentionRoster(documentId: string | null | undefined): MentionCandidate[] {
  const review = useDocument(documentId ?? '').data;
  const participantsQuery = useParticipants(documentId ?? '', Boolean(documentId));
  const selfId = useAuthStore((s) => s.userId);
  const contributors = useMentionContributors();
  return useMemo(() => {
    if (!review || review.anonymous) return [];
    // The token is the profile slug (issue #486), so a slug-less account
    // (predating slugs) cannot be offered. You never ping yourself, so the
    // caller's own row stays out of the picker.
    const roster: MentionCandidate[] = (participantsQuery.data?.participants ?? [])
      .filter(
        (participant) =>
          participant.kind === ParticipantKind.User &&
          participant.slug &&
          participant.principalId !== selfId,
      )
      .map((participant) => ({
        id: participant.principalId,
        name: participant.displayName,
        slug: participant.slug as string,
      }));
    // The owner is mentionable too (the server's access rule names them first)
    // but is rarely on the reviewer roster — lead with them, deduped by id.
    if (
      review.ownerId &&
      review.ownerId !== selfId &&
      review.ownerDisplayName &&
      review.ownerSlug &&
      !roster.some((candidate) => candidate.id === review.ownerId)
    ) {
      roster.unshift({ id: review.ownerId, name: review.ownerDisplayName, slug: review.ownerSlug });
    }
    if (documentId) {
      for (const contributor of contributors) {
        for (const principal of contributor.candidatesFor(documentId)) {
          const slugLower = principal.slug.toLowerCase();
          if (!roster.some((candidate) => candidate.slug.toLowerCase() === slugLower)) {
            roster.push({
              id: principal.id,
              name: principal.name,
              slug: principal.slug,
              kind: principal.kind,
              avatarUrl: principal.avatarUrl ?? null,
              hint: principal.hint,
            });
          }
        }
      }
    }
    return roster;
  }, [contributors, documentId, participantsQuery.data, review, selfId]);
}
