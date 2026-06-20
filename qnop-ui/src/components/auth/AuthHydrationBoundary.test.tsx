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
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthHydrationBoundary } from './AuthHydrationBoundary';
import { useAuthStore } from '../../stores/authStore';

function renderBoundary() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthHydrationBoundary>
        <div>app content</div>
      </AuthHydrationBoundary>
    </QueryClientProvider>,
  );
}

describe('AuthHydrationBoundary', () => {
  it('shows a spinner while hydrating, then renders children and runs hydrate', async () => {
    const hydrate = vi.fn(async () => {
      useAuthStore.setState({ isHydrating: false });
    });
    useAuthStore.setState({ isHydrating: true, hydrate });

    renderBoundary();

    // hydrate is fired and flips isHydrating; children appear.
    await waitFor(() => expect(screen.getByText('app content')).toBeInTheDocument());
    expect(hydrate).toHaveBeenCalledTimes(1);
  });
});
