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

import type { ServerConfigTracking } from '../api/generated';
import { browserRefusesTracking, type ConsentDecision } from './consent';

/** The per-user setting key (UserSettingKey.USAGE_TRACKING_OPT_OUT). */
export const OPT_OUT_KEY = 'usage_tracking_opt_out';

/** Roles left unmeasured unless an operator says otherwise. */
const PRIVILEGED = new Set(['ADMIN', 'AUDITOR']);

export type Gate = 'off' | 'ask' | 'measure';

export interface GateInput {
  tracking: ServerConfigTracking | undefined;
  consent: ConsentDecision;
  optOut: boolean;
  isAuthenticated: boolean;
  role: string | null | undefined;
}

/**
 * Whether anything may be measured at all (issue #666).
 *
 * <p>Four gates, and each can say no on its own: the operator has to have configured measurement,
 * the browser must not be asking to be left alone, the person must not have opted out on their
 * account, and — where consent is required — they must have said yes. Only `measure` causes a
 * script tag to exist; `ask` shows the question and measures nothing until it is answered.
 *
 * <p>A plain function in its own module, so the rules can be read and asserted directly rather than
 * inferred from what turned up in a rendered tree.
 */
export function resolveGate({ tracking, consent, optOut, isAuthenticated, role }: GateInput): Gate {
  if (!tracking) {
    return 'off';
  }
  if (tracking.respectDnt && browserRefusesTracking()) {
    return 'off';
  }
  // Account-level rules need an account: before sign-in there is neither a
  // stored setting nor a role, and the sign-in screen is measured too.
  if (isAuthenticated && optOut) {
    return 'off';
  }
  if (isAuthenticated && !tracking.trackPrivilegedRoles && role && PRIVILEGED.has(role)) {
    return 'off';
  }
  if (!tracking.consentRequired) {
    return 'measure';
  }
  if (consent === 'granted') {
    return 'measure';
  }
  return consent === 'denied' ? 'off' : 'ask';
}
