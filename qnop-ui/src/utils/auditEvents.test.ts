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
import {
  AUDIT_EVENT_TYPES,
  auditEventMeta,
  humanizeToken,
  humanizeWorkflowState,
} from './auditEvents';

describe('auditEventMeta', () => {
  it('returns a label, description and tone for every known event type', () => {
    for (const type of AUDIT_EVENT_TYPES) {
      const meta = auditEventMeta(type);
      expect(meta.label.length).toBeGreaterThan(0);
      expect(meta.description.length).toBeGreaterThan(0);
      expect(meta.tone).toBeDefined();
    }
  });

  it('covers the full ADR-0042 vocabulary including classification and extraction', () => {
    expect(AUDIT_EVENT_TYPES).toEqual(
      expect.arrayContaining([
        'annotation.created',
        'annotation.classified',
        'placement.reattached',
        'placement.repositioned',
        'annotation.auto_closed',
        'workflow.transition',
        'document.due_date.changed',
        'extraction.succeeded',
        'extraction.failed',
      ]),
    );
  });

  it('covers the SYSTEM-stream operator events (storage cleanup, scheduler)', () => {
    expect(AUDIT_EVENT_TYPES).toEqual(
      expect.arrayContaining([
        'storage.orphan.deleted',
        'scheduler.job.run',
        'scheduler.job.updated',
      ]),
    );
    expect(auditEventMeta('scheduler.job.run').label).toBe('Scheduler job run');
    expect(auditEventMeta('scheduler.job.updated').label).toBe('Scheduler job updated');
  });

  it('falls back to the raw type and a neutral tone for an unknown event', () => {
    const meta = auditEventMeta('some.future.event');
    expect(meta.label).toBe('some.future.event');
    expect(meta.tone).toBe('neutral');
    expect(meta.description).toBe('A recorded review event.');
  });
});

describe('humanizeWorkflowState', () => {
  it('maps known states to readable labels', () => {
    expect(humanizeWorkflowState('IN_REVIEW')).toBe('In review');
    expect(humanizeWorkflowState('CHANGES_REQUESTED')).toBe('Changes requested');
  });

  it('passes an unknown (enterprise) state through unchanged', () => {
    expect(humanizeWorkflowState('CUSTOM_STATE')).toBe('CUSTOM_STATE');
  });
});

describe('humanizeToken', () => {
  it('sentence-cases an enum-ish token', () => {
    expect(humanizeToken('HIGH')).toBe('High');
    expect(humanizeToken('CHANGES_REQUESTED')).toBe('Changes requested');
  });
});
