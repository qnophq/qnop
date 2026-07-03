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

import { useEffect, useState } from 'react';
import * as pdfjs from 'pdfjs-dist';
import type { PDFDocumentProxy } from 'pdfjs-dist';

// Vite resolves the worker to an emitted asset relative to the app's base, so
// this also works when the SPA is served single-origin from the Spring app.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString();

export interface PdfDocumentState {
  pdf: PDFDocumentProxy | null;
  error: string | null;
}

/**
 * Parses PDF bytes into a pdf.js document (pixels only — anchoring geometry
 * comes from the server, ADR-0032). The loading task is destroyed on unmount,
 * which also terminates the worker's document handle.
 */
export function usePdfDocument(data: ArrayBuffer | undefined): PdfDocumentState {
  const [state, setState] = useState<PdfDocumentState>({ pdf: null, error: null });

  useEffect(() => {
    if (!data) return undefined;
    let cancelled = false;
    // pdf.js transfers the buffer to its worker; hand it a copy so the
    // query-cached buffer stays usable when the viewer remounts.
    const task = pdfjs.getDocument({ data: data.slice(0) });
    task.promise.then(
      (pdf) => {
        if (!cancelled) setState({ pdf, error: null });
      },
      (error: unknown) => {
        if (!cancelled) {
          setState({
            pdf: null,
            error: error instanceof Error ? error.message : 'Failed to parse PDF',
          });
        }
      },
    );
    return () => {
      cancelled = true;
      setState({ pdf: null, error: null });
      void task.destroy();
    };
  }, [data]);

  return state;
}
