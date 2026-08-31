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

import { describe, expect, it } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { ExtensionsProvider } from './ExtensionsProvider';
import { createExtensionRegistry, useExtensionSlot, type MessageRowContext } from './registry';

const context: MessageRowContext = {
  kind: 'comment',
  documentId: 'd1',
  annotationId: 'a1',
  commentId: 'c1',
  authorId: 'u1',
  own: true,
  annotationStatus: 'OPEN',
  workflowState: 'IN_REVIEW',
  reviewOpen: true,
  body: 'hello',
};

describe('extension registry (#600)', () => {
  it('starts empty and returns contributions in registration order', () => {
    const registry = createExtensionRegistry();
    expect(registry.get('messageActions')).toEqual([]);

    registry.register('messageActions', { id: 'one', render: () => null });
    registry.register('messageActions', { id: 'two', render: () => null });

    expect(registry.get('messageActions').map((c) => c.id)).toEqual(['one', 'two']);
  });

  it('keeps slots independent', () => {
    const registry = createExtensionRegistry();
    registry.register('messageBadges', { id: 'badge', render: () => null });

    expect(registry.get('messageActions')).toEqual([]);
    expect(registry.get('messageBadges').map((c) => c.id)).toEqual(['badge']);
  });

  it('serves a scoped registry through the provider', () => {
    const registry = createExtensionRegistry();
    registry.register('messageBadges', {
      id: 'badge',
      render: (ctx) => <span data-testid="ext-badge">{ctx.commentId}</span>,
    });
    function Probe() {
      const badges = useExtensionSlot('messageBadges');
      return <>{badges.map((c) => c.render(context))}</>;
    }

    render(
      <ExtensionsProvider registry={registry}>
        <Probe />
      </ExtensionsProvider>,
    );

    expect(screen.getByTestId('ext-badge')).toHaveTextContent('c1');
  });

  it('rejects a duplicate contribution id within a slot', () => {
    const registry = createExtensionRegistry();
    registry.register('messageActions', { id: 'dup', render: () => null });

    expect(() => registry.register('messageActions', { id: 'dup', render: () => null })).toThrow(
      /duplicate contribution id/,
    );
  });

  it('keeps the empty snapshot reference-stable across reads', () => {
    const registry = createExtensionRegistry();

    expect(registry.get('messageActions')).toBe(registry.get('messageActions'));
  });

  // The runtime loader (ADR-0039) resolves its dynamic imports AFTER the
  // first render — slots mounted before a registration must pick it up.
  it('re-renders consumers when a contribution registers after mount', () => {
    const registry = createExtensionRegistry();
    function Probe() {
      const badges = useExtensionSlot('messageBadges');
      return <>{badges.map((c) => c.render(context))}</>;
    }
    render(
      <ExtensionsProvider registry={registry}>
        <Probe />
      </ExtensionsProvider>,
    );
    expect(screen.queryByTestId('ext-badge')).not.toBeInTheDocument();

    act(() => {
      registry.register('messageBadges', {
        id: 'late',
        render: () => <span data-testid="ext-badge">late</span>,
      });
    });

    expect(screen.getByTestId('ext-badge')).toBeInTheDocument();
  });

  it('defaults to the (empty) host registry without a provider', () => {
    function Probe() {
      const actions = useExtensionSlot('messageActions');
      return <span data-testid="count">{actions.length}</span>;
    }

    render(<Probe />);

    expect(screen.getByTestId('count')).toHaveTextContent('0');
  });
});
