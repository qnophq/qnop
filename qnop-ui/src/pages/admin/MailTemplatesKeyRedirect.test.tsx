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
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { MailTemplatesKeyRedirect } from './MailTemplatesKeyRedirect';

/** Echoes the key the target route received, so the tests can assert it survived the redirect. */
function KeyEcho() {
  const { key = '' } = useParams();
  return <div>target:{key}</div>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/mail-templates/:key" element={<MailTemplatesKeyRedirect />} />
        <Route path="/admin/email/templates/:key" element={<KeyEcho />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('MailTemplatesKeyRedirect', () => {
  it('redirects a legacy editor URL to the new path, preserving a dotted key', () => {
    renderAt('/admin/mail-templates/auth.password_reset');

    expect(screen.getByText('target:auth.password_reset')).toBeTruthy();
  });

  it('re-encodes keys with reserved characters so they round-trip intact', () => {
    renderAt('/admin/mail-templates/review%2Fdeadline');

    expect(screen.getByText('target:review/deadline')).toBeTruthy();
  });
});
