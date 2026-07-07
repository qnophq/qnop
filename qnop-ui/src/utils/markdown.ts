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

import { remark } from 'remark';
import remarkGfm from 'remark-gfm';
import stripMarkdownPlugin from 'strip-markdown';

// One shared processor: parse GFM, drop the formatting, stringify back to plain
// prose. GFM is included so tables/strikethrough parse into real nodes and
// strip cleanly rather than surviving as literal syntax.
const processor = remark().use(remarkGfm).use(stripMarkdownPlugin);

// The title/search paths strip the same (stable) excerpt strings repeatedly —
// once per card render and once per annotation per keystroke — so a small
// bounded cache keeps parsing off the hot path.
const cache = new Map<string, string>();
const CACHE_LIMIT = 500;

/**
 * Renders a Markdown body down to plain text for surfaces that must stay a
 * one-liner — the tasks card/list/drawer titles and the full-text search
 * (issue #427). Bodies are stored as raw Markdown; a title must read as prose,
 * not `**bold**`. Collapses the residual blank lines a block document leaves so
 * a multi-paragraph body becomes a single clean line. Falls back to the raw
 * text if parsing ever throws — a title is never worth a crash.
 */
export function stripMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return '';
  const cached = cache.get(markdown);
  if (cached !== undefined) return cached;
  let plain: string;
  try {
    plain = String(processor.processSync(markdown)).replace(/\s+/g, ' ').trim();
  } catch {
    plain = markdown.trim();
  }
  if (cache.size >= CACHE_LIMIT) cache.clear();
  cache.set(markdown, plain);
  return plain;
}
