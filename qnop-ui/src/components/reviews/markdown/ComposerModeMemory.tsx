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

import { useEffect, useRef } from 'react';
import { useUpdateUserSettings, useUserSettingValue } from '../../../api/hooks/useUserSettings';
import type { ComposerModeContribution } from '../../../extensions/composerModes';

/** The per-user setting that remembers the composer mode (issue #599). */
export const COMPOSER_MODE_KEY = 'composer_mode';

interface ComposerModeMemoryProps {
  modes: readonly ComposerModeContribution[];
  /** The composer's current mode: `write`, `preview` or a contributed id. */
  mode: string;
  /** Applies the remembered mode once the setting has loaded. */
  onRestore: (mode: string) => void;
}

/**
 * Remembers the last-used composer mode through the user settings (issue
 * #599). Rendered by the composer only while a mode is registered, so that
 * without extensions the composer neither reads nor writes the setting — and
 * needs no query client, which keeps its existing tests untouched.
 *
 * Restores once, when the setting arrives and names a registered mode (or
 * `write`); persists every later change that is a writing mode. Preview is
 * a glance, not a place to come back to, so it is never stored.
 */
export function ComposerModeMemory({ modes, mode, onRestore }: ComposerModeMemoryProps) {
  const stored = useUserSettingValue(COMPOSER_MODE_KEY);
  const { mutate } = useUpdateUserSettings();
  const restored = useRef(false);
  const lastPersisted = useRef<string | null>(null);

  useEffect(() => {
    if (restored.current || stored === undefined) return;
    restored.current = true;
    const known = stored === 'write' || modes.some((candidate) => candidate.id === stored);
    lastPersisted.current = stored;
    if (known && stored !== mode) onRestore(stored);
  }, [stored, modes, mode, onRestore]);

  useEffect(() => {
    if (!restored.current || mode === 'preview' || mode === lastPersisted.current) return;
    lastPersisted.current = mode;
    mutate({ [COMPOSER_MODE_KEY]: mode });
  }, [mode, mutate]);

  return null;
}
