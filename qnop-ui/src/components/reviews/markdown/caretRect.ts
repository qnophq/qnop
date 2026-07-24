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

/** Every computed style that shapes how text wraps and advances in a textarea. */
const MIRROR_STYLES = [
  'font-family',
  'font-size',
  'font-style',
  'font-weight',
  'letter-spacing',
  'line-height',
  'tab-size',
  'text-indent',
  'text-transform',
  'word-spacing',
  'padding-top',
  'padding-right',
  'padding-bottom',
  'padding-left',
  'border-top-width',
  'border-right-width',
  'border-bottom-width',
  'border-left-width',
  'box-sizing',
] as const;

/**
 * The viewport rectangle of the character at `index` inside a textarea,
 * measured through an off-screen mirror that replays the field's text layout
 * (the standard caret-coordinates technique — a textarea exposes no ranges).
 * Anchoring a popover here places it at the caret instead of under the whole
 * field, which keeps the @-mention picker clickable even when the field fills
 * the screen (issue #462 follow-up: in the fullscreen stage a field-anchored
 * picker rendered below the viewport and could not be clicked).
 */
export function caretViewportRect(el: HTMLTextAreaElement, index: number): DOMRect {
  const style = window.getComputedStyle(el);
  const mirror = document.createElement('div');
  for (const property of MIRROR_STYLES) {
    mirror.style.setProperty(property, style.getPropertyValue(property));
  }
  mirror.style.position = 'absolute';
  mirror.style.visibility = 'hidden';
  mirror.style.whiteSpace = 'pre-wrap';
  mirror.style.overflowWrap = 'break-word';
  mirror.style.overflow = 'hidden';
  mirror.style.width = `${el.clientWidth}px`;

  mirror.textContent = el.value.slice(0, index);
  const marker = document.createElement('span');
  // The marker must carry content to occupy the caret's line box.
  marker.textContent = el.value.charAt(index) || '​';
  mirror.appendChild(marker);

  document.body.appendChild(mirror);
  const host = el.getBoundingClientRect();
  const top = host.top + marker.offsetTop - el.scrollTop;
  const left = host.left + marker.offsetLeft - el.scrollLeft;
  const height = marker.offsetHeight || parseFloat(style.lineHeight) || 20;
  mirror.remove();

  return new DOMRect(left, top, 0, height);
}
