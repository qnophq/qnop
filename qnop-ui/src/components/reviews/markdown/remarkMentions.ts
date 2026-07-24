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

/** The mdast surface this plugin touches — kept local, no @types/mdast dependency. */
interface MdNode {
  type: string;
  value?: string;
  url?: string;
  children?: MdNode[];
}

/**
 * `@slug` after start/whitespace/bracket — the same word-boundary and slug-shape
 * rule as the server's MentionParser (letters, digits, inner hyphens, 3–64
 * chars), so what the server resolves is exactly what the client highlights.
 */
const MENTION = /(^|[\s([{>])@([A-Za-z0-9][A-Za-z0-9-]{1,62}[A-Za-z0-9])(?![\w-])/g;

/** Splits one text node around its `@slug` tokens into text + `mention:` link nodes. */
function splitTextNode(node: MdNode): MdNode[] | null {
  const value = node.value ?? '';
  MENTION.lastIndex = 0;
  let match = MENTION.exec(value);
  if (!match) return null;
  const parts: MdNode[] = [];
  let consumed = 0;
  while (match) {
    const tokenStart = match.index + match[1].length;
    if (tokenStart > consumed) {
      parts.push({ type: 'text', value: value.slice(consumed, tokenStart) });
    }
    parts.push({
      type: 'link',
      url: `mention:${match[2]}`,
      children: [{ type: 'text', value: `@${match[2]}` }],
    });
    consumed = tokenStart + match[2].length + 1;
    match = MENTION.exec(value);
  }
  if (consumed < value.length) {
    parts.push({ type: 'text', value: value.slice(consumed) });
  }
  return parts;
}

function walk(node: MdNode) {
  if (!node.children) return;
  // Never descend into links: a mention inside a Markdown link label would nest anchors.
  if (node.type === 'link' || node.type === 'linkReference') return;
  node.children = node.children.flatMap((child) => {
    if (child.type === 'text') {
      return splitTextNode(child) ?? [child];
    }
    walk(child);
    return [child];
  });
}

/**
 * Remark plugin (issue #462): promotes plain GitHub-style `@slug` mention
 * tokens in text to `mention:<slug>` links, which the renderer turns into
 * profile pills. Code spans and fenced blocks are untouched (their content is
 * not a text node), as are existing link labels.
 */
export function remarkMentions() {
  return (tree: unknown) => {
    walk(tree as MdNode);
  };
}
