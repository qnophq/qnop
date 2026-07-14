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

/** Breathing room above a 'start'-aligned target, so it never sticks to the edge. */
const START_GAP_PX = 8;

const GLIDE_MS = 280;

/** One glide per scroller — a newer reveal cancels the one still in flight. */
const inFlight = new WeakMap<HTMLElement, number>();

function scrollParentOf(el: HTMLElement): HTMLElement | null {
  let node = el.parentElement;
  while (node) {
    const style = getComputedStyle(node);
    if (/(auto|scroll)/.test(style.overflowY) && node.scrollHeight > node.clientHeight) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

/**
 * The scrollTop that brings {@code el} into view inside {@code scroller}, or
 * null when no scrolling is needed. 'start' aligns the element's head to the
 * top (minus a small gap); 'nearest' moves the minimum distance and treats a
 * fully visible element as done — in-list clicks never jump.
 */
export function targetScrollTop(
  scroller: HTMLElement,
  el: HTMLElement,
  block: 'start' | 'nearest',
): number | null {
  const scrollerRect = scroller.getBoundingClientRect();
  const rect = el.getBoundingClientRect();
  const elTop = rect.top - scrollerRect.top + scroller.scrollTop;
  const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
  const clamp = (value: number) => Math.min(max, Math.max(0, value));

  if (block === 'start') {
    const target = clamp(elTop - START_GAP_PX);
    return Math.round(target) === Math.round(scroller.scrollTop) ? null : target;
  }
  if (rect.top >= scrollerRect.top && rect.bottom <= scrollerRect.bottom) {
    return null; // fully visible — nearest means: do not move
  }
  if (rect.top < scrollerRect.top) {
    return clamp(elTop);
  }
  return clamp(elTop - (scroller.clientHeight - rect.height));
}

/**
 * Brings an element into view inside its nearest scrollable ancestor with a
 * short ease-out glide (issue #491). Hand-rolled on requestAnimationFrame on
 * purpose: Chrome silently drops a native smooth scrollIntoView whenever
 * another scroll just happened, which makes it useless right after clicks
 * and programmatic scrolling. Reduced motion (and jsdom, which has neither
 * layout nor rAF timing) falls back to an instant jump.
 */
export function revealInScroller(el: HTMLElement, block: 'start' | 'nearest'): void {
  const scroller = scrollParentOf(el);
  if (!scroller) {
    // No measurable scroll parent (jsdom, detached nodes): native fallback.
    el.scrollIntoView?.({ block });
    return;
  }
  const target = targetScrollTop(scroller, el, block);
  if (target == null) {
    return;
  }
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduced || typeof requestAnimationFrame !== 'function') {
    scroller.scrollTop = target;
    return;
  }

  const previous = inFlight.get(scroller);
  if (previous !== undefined) {
    cancelAnimationFrame(previous);
  }
  const from = scroller.scrollTop;
  const delta = target - from;
  const startedAt = performance.now();
  const step = (now: number) => {
    const t = Math.min(1, (now - startedAt) / GLIDE_MS);
    const eased = 1 - Math.pow(1 - t, 3);
    scroller.scrollTop = from + delta * eased;
    if (t < 1) {
      inFlight.set(scroller, requestAnimationFrame(step));
    } else {
      inFlight.delete(scroller);
    }
  };
  inFlight.set(scroller, requestAnimationFrame(step));
}
