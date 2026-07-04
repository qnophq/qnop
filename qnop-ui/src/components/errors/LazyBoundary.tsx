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

import { Suspense, type ReactNode } from 'react';
import { BoundaryFallback } from './BoundaryFallback';
import { ErrorBoundary } from './ErrorBoundary';

// Plain inline styling so the placeholder needs no theme while a chunk loads.
const loadingFallback = (
  <div style={{ padding: '8px 4px', fontSize: 14, color: '#5E6C7B' }}>Loading…</div>
);

/**
 * Wraps a lazily-loaded route in an {@link ErrorBoundary} above a {@link Suspense}
 * (issue #331): the Suspense shows the loading placeholder while the chunk loads,
 * and the boundary catches both a chunk that fails to load and any render crash in
 * the loaded page — surfacing a retryable fallback instead of a blank screen.
 */
export function LazyBoundary({ children }: { children: ReactNode }) {
  return (
    <ErrorBoundary fallback={(_error, reset) => <BoundaryFallback onRetry={reset} />}>
      <Suspense fallback={loadingFallback}>{children}</Suspense>
    </ErrorBoundary>
  );
}
