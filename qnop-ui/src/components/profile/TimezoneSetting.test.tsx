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

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/renderWithProviders';
import { TimezoneSetting } from './TimezoneSetting';

describe('TimezoneSetting', () => {
  it('shows the active zone in the picker and a live clock', () => {
    renderWithProviders(
      <TimezoneSetting value="Europe/Berlin" isExplicit saving={false} onChange={() => {}} />,
    );
    expect(screen.getByRole('combobox', { name: /time zone/i })).toHaveValue('Europe/Berlin');
    // The offset chip reads the live zone (Berlin is GMT+1 or GMT+2 depending on DST).
    expect(screen.getByText(/^GMT[+-]\d/)).toBeInTheDocument();
  });

  it('flags an inherited (non-explicit) zone as following the workspace default', () => {
    renderWithProviders(
      <TimezoneSetting value="UTC" isExplicit={false} saving={false} onChange={() => {}} />,
    );
    expect(screen.getByText(/following the workspace default/i)).toBeInTheDocument();
  });

  // Opening the picker is expensive in jsdom for reasons no user pays for: the
  // engine lists ~418 zones, and `findByRole('option', { name })` computes an
  // accessible name for every one of them — measured at ~1.1 s, on top of ~0.5 s
  // for MUI's own per-option work. Against the 5 s default that left too little
  // headroom under `vitest run`'s parallel load, and this test failed roughly
  // one full run in three (issue #716). The generous budget here is for the
  // query cost, not for slow production code: the offset computation it used to
  // wait on was ~25× faster after caching the Intl formatters.
  it('saves the picked zone', async () => {
    // `delay: null` drops userEvent's artificial inter-event pauses (~0.35 s
    // here) without weakening the interaction it simulates.
    const user = userEvent.setup({ delay: null });
    const onChange = vi.fn();
    renderWithProviders(
      <TimezoneSetting value="UTC" isExplicit={false} saving={false} onChange={onChange} />,
    );

    await user.click(screen.getByRole('combobox', { name: /time zone/i }));
    const option = await screen.findByRole('option', { name: /Asia\/Tokyo/ });
    await user.click(option);

    expect(onChange).toHaveBeenCalledWith('Asia/Tokyo');
  }, 15_000);
});
