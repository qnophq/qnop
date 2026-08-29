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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { useKeepInView } from './useKeepInView';

function Draft({ positionKey }: { positionKey: string | null }) {
  const ref = useKeepInView<HTMLDivElement>(positionKey);
  return <div ref={ref} data-testid="draft" />;
}

const scrollIntoView = vi.fn();

beforeEach(() => {
  vi.useFakeTimers();
  scrollIntoView.mockClear();
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useKeepInView (issue #782)', () => {
  it('scrolls the element into view once per frame after a move', () => {
    const { rerender } = render(<Draft positionKey="0,0" />);
    rerender(<Draft positionKey="0,0.1" />);
    rerender(<Draft positionKey="0,0.2" />);
    expect(scrollIntoView).not.toHaveBeenCalled();

    vi.advanceTimersToNextFrame();

    // Three position changes, one scroll — the held-down key never thrashes.
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest' });
  });

  it('does nothing without a position', () => {
    render(<Draft positionKey={null} />);
    vi.advanceTimersToNextFrame();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('cancels a pending scroll on unmount', () => {
    const { unmount } = render(<Draft positionKey="0,0" />);
    unmount();
    vi.advanceTimersToNextFrame();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
