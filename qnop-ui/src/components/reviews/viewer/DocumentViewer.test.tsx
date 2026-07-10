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
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import type { AnnotationView, RenderedSurface } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { DocumentViewer, type DocumentViewerHandle } from './DocumentViewer';
import type { ViewerTool } from './ViewerToolbar';

// Stub the page stack: DocumentViewer's own logic (page count, scroll spy,
// imperative scroll, mark linking) is what's under test — the pdf.js pixel path
// belongs to PageCanvas / E2E. Page 0 hosts every annotation's highlight anchor
// so the scroll-into-view effects have a target by id.
vi.mock('./SurfacePage', () => ({
  SurfacePage: ({
    pageIndex,
    annotations,
    onSelectAnnotation,
  }: {
    pageIndex: number;
    annotations: { id: string }[];
    onSelectAnnotation: (id: string) => void;
  }) => (
    <div data-testid={`surface-${pageIndex}`}>
      {pageIndex === 0 &&
        annotations.map((a) => <span key={a.id} id={`annotation-highlight-${a.id}`} />)}
      <button onClick={() => onSelectAnnotation('a1')}>select-{pageIndex}</button>
    </div>
  ),
}));

beforeEach(() => {
  // The page stack renders only once the container reports a width. The effect
  // reads `clientWidth` synchronously (the ResizeObserver stub never fires), and
  // jsdom returns 0 for it — so give the container a fixed width to lay out.
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    get: () => 800,
  });
  Element.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  delete (HTMLElement.prototype as { clientWidth?: number }).clientWidth;
  vi.clearAllMocks();
});

function baseProps() {
  return {
    pdf: { numPages: 3 } as unknown as PDFDocumentProxy,
    zoom: 1,
    annotations: [] as AnnotationView[],
    activeAnnotationId: null as string | null,
    onSelectAnnotation: vi.fn(),
    onVisiblePageChange: vi.fn(),
    tool: 'text' as ViewerTool,
    canAnnotate: false,
    pendingAnchor: null,
    onTextSelected: vi.fn(),
    onRegionSelected: vi.fn(),
  };
}

function renderViewer(overrides: Record<string, unknown> = {}) {
  const props = { ...baseProps(), ...overrides };
  const view = render(
    <ThemeProvider theme={buildTheme('light')}>
      <DocumentViewer {...props} />
    </ThemeProvider>,
  );
  const update = (next: Record<string, unknown>) =>
    view.rerender(
      <ThemeProvider theme={buildTheme('light')}>
        <DocumentViewer {...props} {...next} />
      </ThemeProvider>,
    );
  return { props, update };
}

describe('DocumentViewer', () => {
  it('renders one page per pdf page when the server has no surfaces yet', () => {
    renderViewer();

    expect(screen.getAllByTestId(/^surface-/)).toHaveLength(3);
  });

  it('renders one page per server surface once extraction has produced them', () => {
    const surfaces = [{ index: 0 }, { index: 1 }] as RenderedSurface[];
    renderViewer({ surfaces });

    expect(screen.getAllByTestId(/^surface-/)).toHaveLength(2);
  });

  it('scrolls the requested page into view through the imperative handle', () => {
    const handle: { current: DocumentViewerHandle | null } = { current: null };
    render(
      <ThemeProvider theme={buildTheme('light')}>
        <DocumentViewer {...baseProps()} ref={handle} />
      </ThemeProvider>,
    );

    act(() => handle.current?.scrollToPage(1));

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    });
  });

  it('reports the current page to the toolbar on mount and on scroll', async () => {
    const { props } = renderViewer();

    expect(props.onVisiblePageChange).toHaveBeenCalledWith(0);

    fireEvent.scroll(screen.getByLabelText('Document pages'));

    await waitFor(() => expect(props.onVisiblePageChange.mock.calls.length).toBeGreaterThan(1));
  });

  it('scrolls an off-screen active annotation into view once it becomes active', () => {
    // The mark is activated after the page stack has laid out (real flow: a panel
    // click on an already-loaded document), so the highlight anchor exists by then.
    const { update } = renderViewer({ annotations: [{ id: 'a1' }] as AnnotationView[] });

    update({ activeAnnotationId: 'a1' });

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
    });
  });

  it('brings an off-screen hovered mark into view', () => {
    const { update } = renderViewer({ annotations: [{ id: 'a1' }] as AnnotationView[] });

    update({ hoverAnnotationId: 'a1' });

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
    });
  });

  it('forwards a mark selection from the page stack', () => {
    const { props } = renderViewer({ annotations: [{ id: 'a1' }] as AnnotationView[] });

    fireEvent.click(screen.getAllByRole('button', { name: /^select-0$/ })[0]);

    expect(props.onSelectAnnotation).toHaveBeenCalledWith('a1');
  });
});
