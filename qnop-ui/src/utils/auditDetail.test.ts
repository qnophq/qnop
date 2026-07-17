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
import { formatAuditDetail } from './auditDetail';

describe('formatAuditDetail', () => {
  it('renders a workflow transition as old → new', () => {
    expect(formatAuditDetail('workflow.transition', '{"from":"DRAFT","to":"IN_REVIEW"}')).toBe(
      'DRAFT → IN_REVIEW',
    );
  });

  it('fills a missing transition side with an em dash', () => {
    expect(formatAuditDetail('workflow.transition', '{"to":"CANCELLED"}')).toBe('— → CANCELLED');
  });

  it('renders a generic object as a compact key: value list', () => {
    expect(
      formatAuditDetail('document.extraction.failed', '{"versionId":"v1","reason":"BAD_PDF"}'),
    ).toBe('versionId: v1, reason: BAD_PDF');
  });

  it('shows a non-JSON payload verbatim', () => {
    expect(formatAuditDetail('annotation.resolved', 'a1b2c3')).toBe('a1b2c3');
  });

  it('renders an em dash for an absent detail', () => {
    expect(formatAuditDetail('annotation.created', null)).toBe('—');
    expect(formatAuditDetail('annotation.created', undefined)).toBe('—');
    expect(formatAuditDetail('annotation.created', '')).toBe('—');
  });

  it('renders an empty object as an em dash', () => {
    expect(formatAuditDetail('annotation.created', '{}')).toBe('—');
  });

  it('renders a primitive JSON value directly', () => {
    expect(formatAuditDetail('some.event', '42')).toBe('42');
  });

  it('stringifies a nested object value', () => {
    expect(formatAuditDetail('some.event', '{"meta":{"k":1}}')).toBe('meta: {"k":1}');
  });
});
