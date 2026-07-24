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
  /** The immutable profile slug (issue #486) — the mention token IS this slug. */
  slug: string;
}

/**
 * The GitHub-style mention token the composer inserts and the server resolves: {@code @<slug>}.
 * Slug-based because the profile slug (issue #486) is unique, immutable and human-readable — the
 * raw text stays legible and the reference survives display-name changes.
 */
export function mentionToken(candidate: MentionCandidate): string {
  return `@${candidate.slug}`;
}

/**
 * `@slug` after start/whitespace/bracket — the same word-boundary and slug-shape rule as the
 * server's MentionParser (letters, digits, inner hyphens, 3–64 chars), so what the server resolves
 * is exactly what the client highlights. Shared by the Markdown renderer (remarkMentions) and the
 * plain-text excerpt surfaces. Group 1 is the boundary prefix, group 2 the slug.
 */
export const MENTION_TOKEN = /(^|[\s([{>])@([A-Za-z0-9][A-Za-z0-9-]{1,62}[A-Za-z0-9])(?![\w-])/g;

/**
 * Replaces every {@code @slug} mention token in a plain/Markdown text with whatever {@code resolve}
 * returns for the slug — a token whose slug does not resolve stays as-is, mirroring the renderer's
 * raw-@slug fallback. Used by one-line excerpt surfaces that cannot host the full mention pill.
 */
export function replaceMentionTokens(
  text: string,
  resolve: (slug: string) => string | null | undefined,
): string {
  return text.replace(
    MENTION_TOKEN,
    (token, prefix: string, slug: string) =>
      `${prefix}${resolve(slug) ?? token.slice(prefix.length)}`,
  );
}

/**
 * The active {@code @query} immediately before the caret, or {@code null} when the caret is not in a
 * mention. A query starts at an {@code @} that follows whitespace or the start of the text, and runs
 * to the caret across slug characters (word chars and hyphens) — so "email a@b" never triggers the
 * picker. Returns the query text and the index of the {@code @} (where insertion replaces from).
 */
export function activeMentionQuery(
  text: string,
  caret: number,
): { query: string; start: number } | null {
  const before = text.slice(0, caret);
  const match = /(?:^|\s)@([\w-]*)$/.exec(before);
  if (!match) {
    return null;
  }
  const query = match[1];
  return { query, start: caret - query.length - 1 };
}
