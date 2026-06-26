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

import { useCallback, useEffect, useRef, useState } from 'react';
import type { MailTemplatePreviewResponse } from '../generated';
import { adminEmailApi } from '../config';
import { apiErrorMessage } from '../../utils/apiError';

/** Preview sync lifecycle: nothing to render → pending edit → in flight → rendered / failed. */
export type PreviewStatus = 'idle' | 'stale' | 'syncing' | 'live' | 'error';

export interface MailTemplatePreviewInput {
  key: string;
  locale?: string;
  subject: string;
  bodyPlain: string;
  bodyHtml?: string;
  variables?: Record<string, string>;
}

export interface MailTemplatePreviewResult {
  status: PreviewStatus;
  data: MailTemplatePreviewResponse | null;
  error: string | null;
  /** Renders immediately, bypassing the debounce. */
  refresh: () => void;
}

const DEBOUNCE_MS = 500;

/** The enabled-gate: enough content to render, so we never POST a 400-on-mount. */
function isRenderable(input: MailTemplatePreviewInput): boolean {
  return input.key.length > 0 && input.subject.trim() !== '' && input.bodyPlain.trim() !== '';
}

/**
 * Debounced live preview for the mail-template editor (issue #145). Posts the current unsaved draft
 * to the preview endpoint ~500ms after the last edit, sharing one round trip across the panes. A
 * monotonic request-id discards out-of-order responses so a slow earlier render can never overwrite
 * a newer one. The enabled-gate skips the call until key + subject + plain body are non-empty;
 * `refresh()` renders at once, bypassing the debounce.
 */
export function useMailTemplatePreview(input: MailTemplatePreviewInput): MailTemplatePreviewResult {
  const [state, setState] = useState<{
    status: PreviewStatus;
    data: MailTemplatePreviewResponse | null;
    error: string | null;
  }>({ status: 'idle', data: null, error: null });

  const requestIdRef = useRef(0);
  // Always read the latest input from a ref so the debounced run renders the freshest draft.
  const inputRef = useRef(input);
  inputRef.current = input;

  const run = useCallback(async () => {
    const current = inputRef.current;
    if (!isRenderable(current)) {
      return;
    }
    const requestId = (requestIdRef.current += 1);
    setState((prev) => ({ ...prev, status: 'syncing' }));
    try {
      const response = await adminEmailApi.previewMailTemplate({
        key: current.key,
        previewMailTemplateRequest: {
          locale: current.locale,
          subject: current.subject,
          bodyPlain: current.bodyPlain,
          bodyHtml: current.bodyHtml,
          variables: current.variables,
        },
      });
      if (requestId !== requestIdRef.current) {
        return; // a newer request has superseded this response
      }
      setState({ status: 'live', data: response.data, error: null });
    } catch (err) {
      if (requestId !== requestIdRef.current) {
        return;
      }
      setState((prev) => ({
        status: 'error',
        data: prev.data,
        error: apiErrorMessage(err, 'The preview could not be rendered.'),
      }));
    }
  }, []);

  const enabled = isRenderable(input);
  // Serialised trigger: re-run the debounce only when a rendered field actually changes.
  const payload = enabled
    ? JSON.stringify([
        input.key,
        input.locale,
        input.subject,
        input.bodyPlain,
        input.bodyHtml,
        input.variables,
      ])
    : '';

  useEffect(() => {
    if (!enabled) {
      requestIdRef.current += 1; // invalidate any in-flight response
      setState({ status: 'idle', data: null, error: null });
      return;
    }
    setState((prev) => ({ ...prev, status: 'stale' }));
    const timer = setTimeout(() => {
      void run();
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [payload, enabled, run]);

  const refresh = useCallback(() => {
    void run();
  }, [run]);

  return { status: state.status, data: state.data, error: state.error, refresh };
}
