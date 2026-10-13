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
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { applyLocale } from '../../i18n';
import { annotationsApi } from '../config';
import { useConfirmPlacement, useReattachPlacement } from './useAnnotations';

vi.mock('../config', () => ({
  annotationsApi: {
    confirmPlacement: vi.fn(),
    reattachPlacement: vi.fn(),
  },
}));

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

const anchor = {
  region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
};

/** The placement toasts in German (issue #836). */
describe('placement feedback in German', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    await applyLocale('de');
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('confirms a placement, and reports a failed confirmation', async () => {
    const notify = vi.fn();
    vi.mocked(annotationsApi.confirmPlacement).mockResolvedValueOnce({ data: {} } as never);
    const { result } = renderHook(() => useConfirmPlacement(notify), { wrapper });

    act(() => result.current.mutate({ annotationId: 'a1', versionNumber: 2 }));
    await waitFor(() => expect(notify).toHaveBeenLastCalledWith('Position bestätigt.', 'success'));

    vi.mocked(annotationsApi.confirmPlacement).mockRejectedValueOnce(new Error('nope'));
    act(() => result.current.mutate({ annotationId: 'a1', versionNumber: 2 }));
    await waitFor(() =>
      expect(notify).toHaveBeenLastCalledWith(
        'Die Position konnte nicht bestätigt werden.',
        'error',
      ),
    );
  });

  it('re-attaches an annotation, and reports a failed re-attach', async () => {
    const notify = vi.fn();
    vi.mocked(annotationsApi.reattachPlacement).mockResolvedValueOnce({ data: {} } as never);
    const { result } = renderHook(() => useReattachPlacement(notify), { wrapper });

    act(() => result.current.mutate({ annotationId: 'a1', versionNumber: 2, anchor }));
    await waitFor(() =>
      expect(notify).toHaveBeenLastCalledWith('Anmerkung neu verankert.', 'success'),
    );

    vi.mocked(annotationsApi.reattachPlacement).mockRejectedValueOnce(new Error('nope'));
    act(() => result.current.mutate({ annotationId: 'a1', versionNumber: 2, anchor }));
    await waitFor(() =>
      expect(notify).toHaveBeenLastCalledWith(
        'Die Anmerkung konnte nicht neu verankert werden.',
        'error',
      ),
    );
  });
});
