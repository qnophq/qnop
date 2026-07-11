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

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { ReactionGroup } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { ReactionBar } from './ReactionBar';
import { toggleReactionGroup } from './reactionGroups';

const GROUPS: ReactionGroup[] = [
  { emoji: '👍', count: 3, reactedByMe: true, reactors: ['You', 'Anna Krause', 'Ben Roth'] },
  { emoji: '🎉', count: 1, reactedByMe: false, reactors: ['Anna Krause'] },
];

function renderBar(onToggle = vi.fn(), reactions: ReactionGroup[] = GROUPS) {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <ReactionBar reactions={reactions} onToggle={onToggle} />
    </ThemeProvider>,
  );
  return onToggle;
}

describe('ReactionBar (issue #410)', () => {
  it('renders one chip per emoji with the count, own chips pressed', () => {
    renderBar();

    const thumbs = screen.getByRole('button', { name: '👍 3' });
    expect(thumbs).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '🎉 1' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('toggles with the chip’s CURRENT own-state', () => {
    const onToggle = renderBar();

    fireEvent.click(screen.getByRole('button', { name: '👍 3' }));
    expect(onToggle).toHaveBeenCalledWith('👍', true);

    fireEvent.click(screen.getByRole('button', { name: '🎉 1' }));
    expect(onToggle).toHaveBeenCalledWith('🎉', false);
  });

  it('renders nothing without reactions — the first one comes from the hover affordance', () => {
    renderBar(vi.fn(), []);
    expect(screen.queryByTestId('reaction-bar')).not.toBeInTheDocument();
  });

  it('offers a trailing add-reaction affordance', () => {
    renderBar();
    expect(screen.getByRole('button', { name: 'Add reaction' })).toBeInTheDocument();
  });
});

describe('toggleReactionGroup (optimistic flip)', () => {
  it('adds a fresh group for a first reaction', () => {
    const next = toggleReactionGroup([], '👍', 'You');
    expect(next).toEqual([{ emoji: '👍', count: 1, reactedByMe: true, reactors: ['You'] }]);
  });

  it('joins an existing group', () => {
    const next = toggleReactionGroup(
      [{ emoji: '👍', count: 1, reactedByMe: false, reactors: ['Anna'] }],
      '👍',
      'You',
    );
    expect(next).toEqual([{ emoji: '👍', count: 2, reactedByMe: true, reactors: ['Anna', 'You'] }]);
  });

  it('leaves an own group, dropping it entirely at zero', () => {
    const shared = toggleReactionGroup(
      [{ emoji: '👍', count: 2, reactedByMe: true, reactors: ['You', 'Anna'] }],
      '👍',
      'You',
    );
    expect(shared[0]).toMatchObject({ count: 1, reactedByMe: false, reactors: ['Anna'] });

    const solo = toggleReactionGroup(
      [{ emoji: '👍', count: 1, reactedByMe: true, reactors: ['You'] }],
      '👍',
      'You',
    );
    expect(solo).toEqual([]);
  });

  it('never mutates its input', () => {
    const input = [{ emoji: '👍', count: 1, reactedByMe: false, reactors: ['Anna'] }];
    toggleReactionGroup(input, '👍', 'You');
    expect(input[0]).toEqual({ emoji: '👍', count: 1, reactedByMe: false, reactors: ['Anna'] });
  });
});
