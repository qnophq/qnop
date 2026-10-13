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
import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@mui/material/styles';
import { AxiosError, type AxiosResponse } from 'axios';
import { annotationsApi } from '../../../api/config';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { ReactionBar } from './ReactionBar';
import { useToggleAnnotationReaction } from './useReactions';

vi.mock('../../../api/config', () => ({
  annotationsApi: {
    reactToAnnotation: vi.fn(),
    unreactFromAnnotation: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function closedReview(): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, {
    status: 409,
    data: { code: 'REVIEW_CLOSED' },
  } as AxiosResponse);
}

/** Reactions in German (issue #836). */
describe('reactions in German', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('labels the add-reaction affordance in German', () => {
    render(
      <ThemeProvider theme={buildTheme('light', 'de')}>
        <ReactionBar
          reactions={[{ emoji: '👍', count: 2, reactedByMe: false, reactors: ['Anna', 'Ben'] }]}
          onToggle={vi.fn()}
        />
      </ThemeProvider>,
    );
    expect(screen.getByRole('button', { name: 'Reaktion hinzufügen' })).toBeInTheDocument();
  });

  it('explains a refused toggle on a closed review, and a generic failure', async () => {
    const notify = vi.fn();
    const { result } = renderHook(() => useToggleAnnotationReaction('a1', notify), { wrapper });

    vi.mocked(annotationsApi.reactToAnnotation).mockRejectedValueOnce(closedReview());
    act(() => result.current.mutate({ emoji: '👍', reacted: false }));
    await waitFor(() =>
      expect(notify).toHaveBeenLastCalledWith(
        'Das Review ist geschlossen — Reaktionen sind nur noch lesbar.',
        'error',
      ),
    );

    vi.mocked(annotationsApi.reactToAnnotation).mockRejectedValueOnce(new Error('offline'));
    act(() => result.current.mutate({ emoji: '👍', reacted: false }));
    await waitFor(() =>
      expect(notify).toHaveBeenLastCalledWith(
        'Die Reaktion konnte nicht aktualisiert werden.',
        'error',
      ),
    );
  });
});
