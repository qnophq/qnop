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

import type { ParseKeys, TFunction } from 'i18next';

/** A key in the `reviews` catalog (issue #836) — models name text, the views translate it. */
export type ReviewsKey = ParseKeys<'reviews'>;

/**
 * A piece of review text as data: a catalog key with its interpolation values, where a value
 * may itself be a phrase (e.g. the accepted formats inside a file-type rejection). Resolved at
 * render by {@link renderReviewsPhrase}, so it follows a language switch.
 */
export interface ReviewsPhrase {
  key: ReviewsKey;
  values?: Record<string, string | number | ReviewsPhrase>;
}

/** Resolves a phrase (and any nested phrases among its values) in the active language. */
export function renderReviewsPhrase(phrase: ReviewsPhrase, t: TFunction<'reviews'>): string {
  const values = Object.fromEntries(
    Object.entries(phrase.values ?? {}).map(([name, value]) => [
      name,
      typeof value === 'object' ? renderReviewsPhrase(value, t) : value,
    ]),
  );
  return t(phrase.key, values);
}
