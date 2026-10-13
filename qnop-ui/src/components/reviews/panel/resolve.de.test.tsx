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
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { AxiosError, type AxiosResponse } from 'axios';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { DismissControl } from './DismissControl';
import { useDismissWithFeedback, useReopenWithFeedback, useResolveWithFeedback } from './resolve';

type Callbacks = { onSuccess?: () => void; onError?: (error: unknown) => void };

// Each mutation answers with whatever outcome the test queued.
const { outcome } = vi.hoisted(() => ({
  outcome: { current: null as null | { error?: unknown } },
}));
const settle = (_input: unknown, callbacks: Callbacks) => {
  if (outcome.current?.error) callbacks.onError?.(outcome.current.error);
  else callbacks.onSuccess?.();
};
vi.mock('../../../api/hooks/useAnnotations', () => ({
  useResolveAnnotation: () => ({ mutate: settle, isPending: false }),
  useReopenAnnotation: () => ({ mutate: settle, isPending: false }),
  useDismissAnnotation: () => ({ mutate: settle, isPending: false }),
}));

function conflict(code: string): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, {
    status: 409,
    data: { code },
  } as AxiosResponse);
}

const annotation = { id: 'a1', status: AnnotationStatus.Open } as AnnotationView;

/** Resolve, reopen and dismiss report in German (issue #836), conflicts included. */
describe('annotation decisions in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
    outcome.current = null;
  });
  afterEach(async () => {
    cleanup();
    await applyLocale('en');
  });

  it('confirms a resolve and maps the already-resolved conflict', () => {
    const notify = vi.fn();
    const { result } = renderHook(() => useResolveWithFeedback(notify));

    act(() => result.current.resolveWith(annotation));
    expect(notify).toHaveBeenLastCalledWith('Anmerkung erledigt.');

    outcome.current = { error: conflict('ANNOTATION_ALREADY_RESOLVED') };
    act(() => result.current.resolveWith(annotation));
    expect(notify).toHaveBeenLastCalledWith('Diese Anmerkung wurde bereits erledigt.', 'error');
  });

  it('maps the reopen and dismiss conflicts, and falls back for unknown failures', () => {
    const notify = vi.fn();
    const reopen = renderHook(() => useReopenWithFeedback(notify)).result;
    const dismiss = renderHook(() => useDismissWithFeedback(notify)).result;

    outcome.current = { error: conflict('REVIEW_CLOSED') };
    act(() => reopen.current.reopenWith(annotation));
    expect(notify).toHaveBeenLastCalledWith(
      'Das Review ist abgeschlossen — geklärte Anmerkungen bleiben geschlossen.',
      'error',
    );
    act(() => dismiss.current.dismissWith(annotation, 'Autor nicht erreichbar'));
    expect(notify).toHaveBeenLastCalledWith(
      'Das Review ist geschlossen — es gibt nichts mehr zu verwerfen.',
      'error',
    );

    outcome.current = { error: new Error('offline') };
    act(() => dismiss.current.dismissWith(annotation, 'Autor nicht erreichbar'));
    expect(notify).toHaveBeenLastCalledWith(
      'Die Anmerkung konnte nicht verworfen werden.',
      'error',
    );
  });

  it('renders the dismiss prompt in German', () => {
    render(
      <ThemeProvider theme={buildTheme('light', 'de')}>
        <DismissControl disabled={false} onDismiss={vi.fn()} />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Verwerfen…' }));
    expect(
      screen.getByRole('textbox', { name: 'Begründung für das Verwerfen (Pflichtfeld)' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abbrechen' })).toBeInTheDocument();
    expect(
      screen.getByText(
        'Die Begründung wird im Thread veröffentlicht; der Verfasser kann die Anmerkung wieder öffnen.',
      ),
    ).toBeInTheDocument();
  });
});
