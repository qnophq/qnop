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

import { useRef } from 'react';
import type { RenderedTextSpan } from '../../../api/generated';
import type { TextSelectionOffsets } from './anchoring';

interface TextSpanLayerProps {
  spans: RenderedTextSpan[];
  surfaceIndex: number;
  /** Current display height of the page in CSS pixels — sizes the invisible glyphs. */
  pageHeight: number;
  enabled: boolean;
  onTextSelected: (selection: TextSelectionOffsets) => void;
}

/** Finds the enclosing span element carrying canonical-text offsets. */
function spanElementFor(node: Node | null): HTMLElement | null {
  if (!node) return null;
  const element = node instanceof HTMLElement ? node : node.parentElement;
  return element?.closest<HTMLElement>('[data-span-start]') ?? null;
}

/**
 * An invisible, selectable text layer built from the server-extracted spans
 * (ADR-0032 — the client is not the authority on where text is; it only makes
 * the server's text selectable). Each span is absolutely positioned at its
 * normalized box, like pdf.js's own text layer, but the offsets attached to
 * each span are the canonical-text offsets the anchor model needs (ADR-0009).
 */
export function TextSpanLayer({
  spans,
  surfaceIndex,
  pageHeight,
  enabled,
  onTextSelected,
}: TextSpanLayerProps) {
  const rootRef = useRef<HTMLDivElement>(null);

  const handlePointerUp = () => {
    const selection = window.getSelection();
    const root = rootRef.current;
    if (!selection || selection.rangeCount === 0 || selection.isCollapsed || !root) return;
    const range = selection.getRangeAt(0);
    const startElement = spanElementFor(range.startContainer);
    const endElement = spanElementFor(range.endContainer);
    // Both ends must sit on this surface's spans; cross-surface selections are ignored.
    if (
      !startElement ||
      !endElement ||
      !root.contains(startElement) ||
      !root.contains(endElement)
    ) {
      return;
    }
    const start =
      Number(startElement.dataset.spanStart) +
      (range.startContainer.nodeType === Node.TEXT_NODE ? range.startOffset : 0);
    const end =
      Number(endElement.dataset.spanStart) +
      (range.endContainer.nodeType === Node.TEXT_NODE
        ? range.endOffset
        : Number(endElement.dataset.spanLength));
    if (end <= start) return;
    selection.removeAllRanges();
    onTextSelected({ surfaceIndex, start, end });
  };

  return (
    <div
      ref={rootRef}
      data-testid={`text-layer-${surfaceIndex}`}
      onPointerUp={handlePointerUp}
      style={{
        position: 'absolute',
        inset: 0,
        overflow: 'hidden',
        cursor: enabled ? 'text' : 'default',
        pointerEvents: enabled ? 'auto' : 'none',
        userSelect: enabled ? 'text' : 'none',
      }}
    >
      {spans.map((span) => (
        <span
          key={span.startOffset}
          data-span-start={span.startOffset}
          data-span-length={span.text.length}
          style={{
            position: 'absolute',
            left: `${span.box.x * 100}%`,
            top: `${span.box.y * 100}%`,
            width: `${span.box.width * 100}%`,
            height: `${span.box.height * 100}%`,
            color: 'transparent',
            whiteSpace: 'pre',
            overflow: 'hidden',
            // Approximate glyph metrics: selection needs plausible hit targets,
            // not typographic fidelity — the anchor is built from offsets.
            fontSize: `${Math.max(span.box.height * pageHeight * 0.85, 6)}px`,
            lineHeight: `${Math.max(span.box.height * pageHeight, 7)}px`,
          }}
        >
          {span.text}
        </span>
      ))}
    </div>
  );
}
