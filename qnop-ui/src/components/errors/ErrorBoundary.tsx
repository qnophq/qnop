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

import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
  /**
   * What to render when a descendant throws. Either a static node, or a render
   * function that receives the error and a `reset` callback to retry.
   */
  fallback: ReactNode | ((error: Error, reset: () => void) => ReactNode);
  /**
   * When any value in this array changes between renders, the boundary clears its
   * error and re-renders its children — e.g. navigating to a different document.
   */
  resetKeys?: readonly unknown[];
  /** Optional hook for reporting; called once per caught error. */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * A React error boundary (issue #331). A throw in a descendant — a crashing
 * viewer, annotation panel, or a lazy chunk that fails to load — is caught here
 * and rendered as a scoped fallback instead of unmounting the whole page. React
 * has no functional equivalent, so this stays a class component. Pair one above
 * every {@code Suspense} boundary so both the loading and the failure states are
 * handled.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Surface the crash for diagnosis rather than swallowing it silently; a real
    // error reporter can be wired through `onError` later.
    console.error('Unhandled error caught by ErrorBoundary:', error, info.componentStack);
    this.props.onError?.(error, info);
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps): void {
    if (this.state.error !== null && this.resetKeysChanged(prevProps.resetKeys)) {
      this.reset();
    }
  }

  private resetKeysChanged(previous: readonly unknown[] | undefined): boolean {
    const next = this.props.resetKeys;
    if (previous === undefined || next === undefined) {
      return previous !== next;
    }
    return (
      previous.length !== next.length || previous.some((value, i) => !Object.is(value, next[i]))
    );
  }

  private reset = (): void => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (error === null) {
      return this.props.children;
    }
    const { fallback } = this.props;
    return typeof fallback === 'function' ? fallback(error, this.reset) : fallback;
  }
}
