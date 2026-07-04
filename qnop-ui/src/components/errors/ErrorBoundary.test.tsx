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

import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { BoundaryFallback } from './BoundaryFallback';
import { ErrorBoundary } from './ErrorBoundary';

// A component whose throwing is controlled by a module flag, so a retry can
// re-render it into a working state.
let shouldThrow = true;
function MaybeBomb() {
  if (shouldThrow) {
    throw new Error('kaboom');
  }
  return <div>recovered content</div>;
}

function Bomb(): never {
  throw new Error('kaboom');
}

function withTheme(node: ReactNode) {
  return <ThemeProvider theme={buildTheme('light')}>{node}</ThemeProvider>;
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    shouldThrow = true;
    // React logs caught render errors to console.error; keep the test output clean.
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders children when nothing throws', () => {
    render(
      <ErrorBoundary fallback={<div>fallback</div>}>
        <div>healthy child</div>
      </ErrorBoundary>,
    );

    expect(screen.getByText('healthy child')).toBeInTheDocument();
    expect(screen.queryByText('fallback')).not.toBeInTheDocument();
  });

  it('renders the fallback when a descendant throws', () => {
    render(
      <ErrorBoundary fallback={<div>caught it</div>}>
        <Bomb />
      </ErrorBoundary>,
    );

    expect(screen.getByText('caught it')).toBeInTheDocument();
  });

  it('reports the caught error through onError', () => {
    const onError = vi.fn();
    render(
      <ErrorBoundary fallback={<div>fallback</div>} onError={onError}>
        <Bomb />
      </ErrorBoundary>,
    );

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toBeInstanceOf(Error);
  });

  it('recovers when the fallback retry is invoked', () => {
    render(
      <ErrorBoundary fallback={(_error, reset) => <button onClick={reset}>retry</button>}>
        <MaybeBomb />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('button', { name: 'retry' })).toBeInTheDocument();

    shouldThrow = false;
    fireEvent.click(screen.getByRole('button', { name: 'retry' }));

    expect(screen.getByText('recovered content')).toBeInTheDocument();
  });

  it('clears the error when resetKeys change', () => {
    function Scene({ resetKey, boom }: { resetKey: number; boom: boolean }) {
      return (
        <ErrorBoundary resetKeys={[resetKey]} fallback={<div>fallback</div>}>
          {boom ? <Bomb /> : <div>ok now</div>}
        </ErrorBoundary>
      );
    }

    const { rerender } = render(<Scene resetKey={1} boom />);
    expect(screen.getByText('fallback')).toBeInTheDocument();

    rerender(<Scene resetKey={2} boom={false} />);
    expect(screen.getByText('ok now')).toBeInTheDocument();
  });

  it('uses BoundaryFallback as a retryable, in-brand default', () => {
    render(
      withTheme(
        <ErrorBoundary fallback={(_error, reset) => <BoundaryFallback onRetry={reset} />}>
          <Bomb />
        </ErrorBoundary>,
      ),
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
  });
});
