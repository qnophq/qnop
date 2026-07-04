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

import { useState } from 'react';

/** The tasks view's two presentations (issue #393). */
export type TasksViewMode = 'board' | 'list';

const TASKS_VIEW_KEY = 'qnop-tasks-view';

function storedMode(): TasksViewMode {
  try {
    return localStorage.getItem(TASKS_VIEW_KEY) === 'list' ? 'list' : 'board';
  } catch {
    return 'board';
  }
}

/**
 * The persisted board/list choice (issue #393) — a personal preference like
 * the other viewer choices, so localStorage rather than the URL. Defaults to
 * the board.
 */
export function useTasksViewMode(): [TasksViewMode, (mode: TasksViewMode) => void] {
  const [mode, setMode] = useState<TasksViewMode>(storedMode);

  const set = (value: TasksViewMode) => {
    setMode(value);
    try {
      localStorage.setItem(TASKS_VIEW_KEY, value);
    } catch {
      // best-effort persistence
    }
  };

  return [mode, set];
}
