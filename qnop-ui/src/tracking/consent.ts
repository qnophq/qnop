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

const STORAGE_KEY = 'qnop-tracking-consent';

export type ConsentDecision = 'granted' | 'denied' | 'unanswered';

/**
 * Whether this browser has answered the measurement question (issue #666).
 *
 * <p>Kept in the browser rather than at the account, because the question is asked before anyone
 * has signed in — the sign-in screen is measured too — and an answer that only counted after login
 * would mean measuring people who had not been asked yet. The personal opt-out in the profile is
 * the account-level control, and it overrides this either way.
 */
export function readConsent(): ConsentDecision {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'granted' || stored === 'denied' ? stored : 'unanswered';
  } catch {
    // Storage blocked: treat as unanswered, which measures nothing.
    return 'unanswered';
  }
}

export function writeConsent(decision: Exclude<ConsentDecision, 'unanswered'>): void {
  try {
    localStorage.setItem(STORAGE_KEY, decision);
  } catch {
    // Best-effort; the question is simply asked again next time.
  }
}

/** Forgets the answer, so the question is asked afresh (used when revoking in the profile). */
export function clearConsent(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing to do.
  }
}

/**
 * Whether the browser has asked not to be tracked.
 *
 * <p>Both signals count: `doNotTrack` is the older one browsers still send, `globalPrivacyControl`
 * the one that carries legal weight in a growing number of places. Either is an answer, and the
 * answer is no.
 */
export function browserRefusesTracking(): boolean {
  const nav = navigator as Navigator & {
    doNotTrack?: string | null;
    globalPrivacyControl?: boolean;
    msDoNotTrack?: string | null;
  };
  const dnt =
    nav.doNotTrack ?? (window as unknown as { doNotTrack?: string | null }).doNotTrack ?? null;
  return (
    dnt === '1' || dnt === 'yes' || nav.msDoNotTrack === '1' || nav.globalPrivacyControl === true
  );
}
