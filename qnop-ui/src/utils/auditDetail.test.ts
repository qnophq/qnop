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
  it('renders a workflow transition with human state labels', () => {
    expect(formatAuditDetail('workflow.transition', '{"from":"DRAFT","to":"IN_REVIEW"}')).toBe(
      'Draft → In review',
    );
  });

  it('fills a missing transition side with an em dash', () => {
    expect(formatAuditDetail('workflow.transition', '{"to":"CANCELLED"}')).toBe('— → Cancelled');
  });

  it('renders a placement on its version number, not the raw annotation id', () => {
    expect(
      formatAuditDetail('placement.confirmed', '{"annotationId":"a1b2c3","versionNumber":3}'),
    ).toBe('On version 3');
    expect(
      formatAuditDetail('placement.reattached', '{"annotationId":"a1b2c3","versionNumber":2}'),
    ).toBe('On version 2');
    expect(
      formatAuditDetail('placement.repositioned', '{"annotationId":"a1b2c3","versionNumber":2}'),
    ).toBe('On version 2');
  });

  it('renders a classification from its type and priority', () => {
    expect(formatAuditDetail('annotation.classified', '{"type":"ISSUE","priority":"HIGH"}')).toBe(
      'As Issue · High priority',
    );
  });

  it('renders a due-date change with the caller’s date formatter', () => {
    const fmt = (iso: string) => `[${iso}]`;
    expect(
      formatAuditDetail(
        'document.due_date.changed',
        '{"from":null,"to":"2026-08-01T00:00:00Z"}',
        fmt,
      ),
    ).toBe('Set to [2026-08-01T00:00:00Z]');
    expect(
      formatAuditDetail(
        'document.due_date.changed',
        '{"from":"2026-08-01T00:00:00Z","to":"2026-08-08T00:00:00Z"}',
        fmt,
      ),
    ).toBe('[2026-08-01T00:00:00Z] → [2026-08-08T00:00:00Z]');
    expect(
      formatAuditDetail(
        'document.due_date.changed',
        '{"from":"2026-08-01T00:00:00Z","to":null}',
        fmt,
      ),
    ).toBe('Cleared (was [2026-08-01T00:00:00Z])');
  });

  it('renders an extraction failure by its reason', () => {
    expect(formatAuditDetail('extraction.failed', '{"versionId":"v1","reason":"BAD_PDF"}')).toBe(
      'Reason: BAD_PDF',
    );
  });

  it('renders an em dash for events whose meaning is carried by the label alone', () => {
    expect(formatAuditDetail('annotation.created', '{"annotationId":"a1"}')).toBe('—');
    expect(formatAuditDetail('annotation.resolved', '{"annotationId":"a1"}')).toBe('—');
    expect(formatAuditDetail('extraction.succeeded', '{"versionId":"v1"}')).toBe('—');
  });

  it('shows a non-JSON payload verbatim', () => {
    expect(formatAuditDetail('annotation.resolved', 'not-json')).toBe('not-json');
  });

  it('renders an em dash for an absent detail', () => {
    expect(formatAuditDetail('annotation.created', null)).toBe('—');
    expect(formatAuditDetail('annotation.created', undefined)).toBe('—');
    expect(formatAuditDetail('annotation.created', '')).toBe('—');
  });

  it('falls back to a compact key: value list for an unknown event shape', () => {
    expect(formatAuditDetail('some.future.event', '{"meta":{"k":1}}')).toBe('meta: {"k":1}');
    expect(formatAuditDetail('some.future.event', '42')).toBe('42');
  });
});
