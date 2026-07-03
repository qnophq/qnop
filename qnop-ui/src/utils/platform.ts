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

/** True on Apple platforms — where shortcuts speak ⌘ instead of Alt/Ctrl. */
export function isApplePlatform(): boolean {
  return /Mac|iPhone|iPod|iPad/i.test(navigator.platform || navigator.userAgent);
}

/**
 * True when a keyboard event is the submit chord: ⌘+Enter (Mac) or Alt+Enter
 * (Windows/Linux). Both modifiers are accepted on every platform.
 */
export function isSubmitShortcut(event: {
  key: string;
  metaKey: boolean;
  altKey: boolean;
}): boolean {
  return event.key === 'Enter' && (event.metaKey || event.altKey);
}

/** The submit chord spelled for the current platform, e.g. "⌘⏎" or "Alt+⏎". */
export function submitShortcutLabel(): string {
  return isApplePlatform() ? '⌘⏎' : 'Alt+⏎';
}
