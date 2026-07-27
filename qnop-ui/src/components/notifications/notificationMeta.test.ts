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
import { NotificationType } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { notificationLabel, notificationTone } from './notificationMeta';

const THEME = buildTheme('light');
const ALL_TYPES = Object.values(NotificationType);

describe('notificationLabel', () => {
  it('names every type in the contract', () => {
    for (const type of ALL_TYPES) {
      expect(notificationLabel(type)).not.toBe('Notification');
    }
    expect(notificationLabel(NotificationType.Mention)).toBe('Mention');
    expect(notificationLabel(NotificationType.ParticipantAdded)).toBe('Invitation');
  });

  it('falls back rather than rendering nothing for an unknown type', () => {
    // The contract can gain a type before the UI knows it — a new enum value
    // must degrade to a generic label, never to `undefined`.
    expect(notificationLabel('SOMETHING_NEW' as NotificationType)).toBe('Notification');
  });
});

describe('notificationTone', () => {
  it('gives every type a colour from the brand ramp', () => {
    const ramp: readonly string[] = THEME.qnop.avatarPalette;
    for (const type of ALL_TYPES) {
      expect(ramp).toContain(notificationTone(type, THEME));
    }
  });

  it('keeps the loud types visually distinct from one another', () => {
    // The whole point of the crest is that a mention does not look like an
    // invitation or a decision at a glance.
    const tones = [
      notificationTone(NotificationType.Mention, THEME),
      notificationTone(NotificationType.ParticipantAdded, THEME),
      notificationTone(NotificationType.AnnotationDecided, THEME),
      notificationTone(NotificationType.VersionUploaded, THEME),
    ];
    expect(new Set(tones).size).toBe(tones.length);
  });

  it('falls back to the primary colour for an unknown type', () => {
    expect(notificationTone('SOMETHING_NEW' as NotificationType, THEME)).toBe(
      THEME.qnop.avatarPalette[0],
    );
  });
});
