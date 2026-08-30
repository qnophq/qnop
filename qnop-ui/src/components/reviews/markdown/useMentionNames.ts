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

import { ParticipantKind } from '../../../api/generated';
import { useDocument } from '../../../api/hooks/useDocuments';
import { useParticipants } from '../../../api/hooks/useReviews';
import { replaceMentionTokens } from './mentionToken';
import { resolveContributedMention, useMentionContributors } from '../../../extensions/mentions';

/**
 * Resolves {@code @slug} mention tokens to display names for the plain-text
 * excerpt surfaces (the collapsed annotation rows) that cannot host the full
 * mention pill. Names come from the review's cached roster — its USER
 * participants plus the owner, the exact circle the picker offers — so a list
 * of fifty rows costs no extra fetches. Unlike {@link useMentionRoster} the
 * caller themself IS included: your own mention must read as your name too.
 * Matching ignores case, as on the server; a slug outside the roster stays a
 * raw {@code @slug}, mirroring the renderer's fallback. Contributed mention
 * namespaces (issue #598) resolve through the registered contributors, after
 * the roster so a user name always wins its own slug.
 */
export function useMentionNames(documentId: string | null | undefined): (text: string) => string {
  const review = useDocument(documentId ?? '').data;
  const participants = useParticipants(documentId ?? '', Boolean(documentId)).data?.participants;
  const contributors = useMentionContributors();
  // No manual useMemo: the React Compiler memoizes this against the two cache reads.
  const names = new Map<string, string>();
  for (const participant of participants ?? []) {
    if (participant.kind === ParticipantKind.User && participant.slug) {
      names.set(participant.slug.toLowerCase(), participant.displayName);
    }
  }
  if (review?.ownerSlug && review.ownerDisplayName) {
    names.set(review.ownerSlug.toLowerCase(), review.ownerDisplayName);
  }
  if (names.size === 0 && contributors.length === 0) {
    return (text: string) => text;
  }
  return (text: string) =>
    replaceMentionTokens(
      text,
      (slug) =>
        names.get(slug.toLowerCase()) ?? resolveContributedMention(contributors, slug)?.name,
    );
}
