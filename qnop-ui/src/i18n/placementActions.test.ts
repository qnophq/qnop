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
import { SUPPORTED_LOCALES } from './locales';

type Workspace = { annotation: { placementActions: Record<string, string> } };

const workspaceCatalogs = import.meta.glob<Workspace>('./*/workspace.json', {
  eager: true,
  import: 'default',
});

/**
 * Re-attach (choose the passage again for an annotation that lost its place) and re-position
 * (move a healthy annotation, #562) sit side by side in the annotation menu, so they must read
 * differently in every language (issue #863).
 */
describe('annotation placement actions', () => {
  it.each(SUPPORTED_LOCALES)('%s words re-attach and re-position differently', (locale) => {
    const actions = workspaceCatalogs[`./${locale}/workspace.json`].annotation.placementActions;
    // Present and non-empty first, so two missing keys cannot pass as "different".
    for (const key of ['reattach', 'reposition', 'reattachHint', 'repositionHint']) {
      expect(actions[key]?.trim()).toBeTruthy();
    }
    expect(actions.reattach).not.toBe(actions.reposition);
    expect(actions.reattachHint).not.toBe(actions.repositionHint);
  });
});
