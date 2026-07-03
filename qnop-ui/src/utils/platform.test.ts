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

import { afterEach, describe, expect, it } from 'vitest';
import { isApplePlatform, isSubmitShortcut, submitShortcutLabel } from './platform';

const originalPlatform = navigator.platform;

function setPlatform(value: string) {
  Object.defineProperty(navigator, 'platform', { value, configurable: true });
}

afterEach(() => {
  setPlatform(originalPlatform);
});

describe('isApplePlatform', () => {
  it('detects macOS and iOS platforms', () => {
    setPlatform('MacIntel');
    expect(isApplePlatform()).toBe(true);
    setPlatform('iPhone');
    expect(isApplePlatform()).toBe(true);
  });

  it('rejects other platforms', () => {
    setPlatform('Win32');
    expect(isApplePlatform()).toBe(false);
    setPlatform('Linux x86_64');
    expect(isApplePlatform()).toBe(false);
  });
});

describe('isSubmitShortcut', () => {
  it('accepts Cmd+Enter and Alt+Enter', () => {
    expect(isSubmitShortcut({ key: 'Enter', metaKey: true, altKey: false })).toBe(true);
    expect(isSubmitShortcut({ key: 'Enter', metaKey: false, altKey: true })).toBe(true);
  });

  it('rejects plain Enter and modified non-Enter keys', () => {
    expect(isSubmitShortcut({ key: 'Enter', metaKey: false, altKey: false })).toBe(false);
    expect(isSubmitShortcut({ key: 'a', metaKey: true, altKey: false })).toBe(false);
  });
});

describe('submitShortcutLabel', () => {
  it('speaks the platform dialect', () => {
    setPlatform('MacIntel');
    expect(submitShortcutLabel()).toBe('⌘⏎');
    setPlatform('Win32');
    expect(submitShortcutLabel()).toBe('Alt+⏎');
  });
});
