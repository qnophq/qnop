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
import { act, renderHook } from '@testing-library/react';
import { registerComposerMode, unregisterComposerMode, useComposerModes } from './composerModes';

const surface = () => null;

afterEach(() => {
  unregisterComposerMode('a');
  unregisterComposerMode('b');
});

describe('composer mode registry (#599)', () => {
  it('publishes registrations to subscribers in order and replaces by id', () => {
    const { result } = renderHook(() => useComposerModes());
    expect(result.current).toEqual([]);

    act(() => {
      registerComposerMode({ id: 'a', label: 'A', Surface: surface });
      registerComposerMode({ id: 'b', label: 'B', Surface: surface });
    });
    expect(result.current.map((mode) => mode.label)).toEqual(['A', 'B']);

    act(() => {
      registerComposerMode({ id: 'a', label: 'A2', Surface: surface });
    });
    expect(result.current.map((mode) => mode.label)).toEqual(['B', 'A2']);
  });

  it('unregisters through the returned disposer', () => {
    const { result } = renderHook(() => useComposerModes());
    let dispose = () => {};
    act(() => {
      dispose = registerComposerMode({ id: 'a', label: 'A', Surface: surface });
    });
    expect(result.current).toHaveLength(1);
    act(() => dispose());
    expect(result.current).toEqual([]);
  });

  it("rejects the composer's own mode ids", () => {
    expect(() => registerComposerMode({ id: 'write', label: 'W', Surface: surface })).toThrow(
      /reserved/,
    );
    expect(() => registerComposerMode({ id: 'preview', label: 'P', Surface: surface })).toThrow(
      /reserved/,
    );
  });
});
