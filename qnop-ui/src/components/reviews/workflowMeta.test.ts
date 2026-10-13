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
import i18n from '../../i18n';
import { renderReviewsPhrase } from './reviewsI18n';
import { workflowLabel, workflowLabelKey } from './workflowMeta';

describe('workflowLabelKey (issue #836)', () => {
  it('names every known state by a catalog key', () => {
    expect(workflowLabelKey('IN_REVIEW')).toBe('workflow.states.IN_REVIEW');
    expect(workflowLabelKey('CHANGES_REQUESTED')).toBe('workflow.states.CHANGES_REQUESTED');
    expect(workflowLabelKey('CANCELLED')).toBe('workflow.states.CANCELLED');
  });

  it('leaves an unknown (enterprise) state to the humanized fallback', () => {
    expect(workflowLabelKey('LEGAL_HOLD')).toBeUndefined();
    expect(workflowLabel('LEGAL_HOLD')).toBe('Legal hold');
  });
});

describe('renderReviewsPhrase', () => {
  it('resolves nested phrases among the values', () => {
    const t = i18n.getFixedT('en', 'reviews');
    expect(
      renderReviewsPhrase(
        { key: 'upload.validation.type', values: { formats: { key: 'upload.formats.pdfOrWord' } } },
        t,
      ),
    ).toBe('Only PDF or Word documents are supported.');
  });
});
