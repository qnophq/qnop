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

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ShowcaseReviewStage } from './ShowcaseReviewStage';

describe('ShowcaseReviewStage', () => {
  it('puts the reviewed document in focus with the user as reviewer', () => {
    render(<ShowcaseReviewStage />);
    // The document hero: title, version and both annotation markers.
    expect(screen.getByText('Release notes')).toBeInTheDocument();
    expect(screen.getByText('v4')).toBeInTheDocument();
    // The background documents deepen the stack.
    expect(screen.getByText('Master agreement')).toBeInTheDocument();
    expect(screen.getByText('v2')).toBeInTheDocument();
    expect(screen.getByText('Audit report')).toBeInTheDocument();
    expect(screen.getByText('v1')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    // The user appears as the reviewer working the thread.
    expect(screen.getByText('Mira Novak')).toBeInTheDocument();
    expect(screen.getByText('Reviewer')).toBeInTheDocument();
    expect(screen.getByText('Resolve')).toBeInTheDocument();
    // The gamified satellite stays.
    expect(screen.getByText('Annotation resolved')).toBeInTheDocument();
  });

  it('is decorative: hidden from assistive tech and inert to pointers', () => {
    const { container } = render(<ShowcaseReviewStage />);
    const root = container.firstElementChild as HTMLElement;
    expect(root).toHaveAttribute('aria-hidden', 'true');
    expect(root).toHaveStyle({ pointerEvents: 'none' });
  });
});
