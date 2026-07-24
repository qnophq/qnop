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

/** A person the @-picker can offer — one member of the document roster (issue #462). */
export interface MentionCandidate {
  id: string;
  name: string;
}

/**
 * The canonical, id-based mention token the composer inserts and the server resolves:
 * {@code [@Display Name](mention:<uuid>)}. Id-based (not @username) because OIDC users may have no
 * username; the @ lives inside the link text so the whole "@Name" renders as one pill.
 */
export function mentionToken(candidate: MentionCandidate): string {
  return `[@${candidate.name}](mention:${candidate.id})`;
}

/**
 * The active {@code @query} immediately before the caret, or {@code null} when the caret is not in a
 * mention. A query starts at an {@code @} that follows whitespace or the start of the text, and runs
 * to the caret across word characters only — so "email a@b" or a completed token never re-triggers
 * the picker. Returns the query text and the index of the {@code @} (where insertion replaces from).
 */
export function activeMentionQuery(
  text: string,
  caret: number,
): { query: string; start: number } | null {
  const before = text.slice(0, caret);
  const match = /(?:^|\s)@(\w*)$/.exec(before);
  if (!match) {
    return null;
  }
  const query = match[1];
  return { query, start: caret - query.length - 1 };
}
