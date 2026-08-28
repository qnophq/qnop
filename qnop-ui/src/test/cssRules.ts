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

/**
 * Returns the declaration block of the `::before` rule Emotion emitted for one
 * of the element's own classes, or `undefined` when there is none.
 *
 * <p>jsdom lays out no pseudo-elements, so a test that cares about a `::before`
 * overlay (the 44 px touch target of #724, say) asserts on the CSS that was
 * emitted — which is exactly what a browser would apply.
 */
export function pseudoBeforeRule(element: Element): string | undefined {
  const css = Array.from(document.querySelectorAll('style'))
    .map((style) => style.textContent ?? '')
    .join('\n');
  for (const className of Array.from(element.classList)) {
    const match = css.match(new RegExp(`\\.${className}::before\\{([^}]*)\\}`));
    if (match) return match[1];
  }
  return undefined;
}

/** True when the element's `::before` overlay is the #724 44 px touch target. */
export function hasTouchTarget(element: Element): boolean {
  const rule = pseudoBeforeRule(element);
  return (
    rule !== undefined &&
    rule.includes('position:absolute') &&
    rule.includes('width:max(100%, 44px)') &&
    rule.includes('height:max(100%, 44px)')
  );
}
