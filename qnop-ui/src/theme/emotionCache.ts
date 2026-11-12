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

import createCache, { type EmotionCache } from '@emotion/cache';
import { readCspNonce } from '../utils/cspNonce';

/**
 * The Emotion cache every MUI style goes through, carrying the page's CSP
 * nonce (issue #913): each `<style>` Emotion inserts gets it, so the SPA's
 * policy needs no `'unsafe-inline'`. `key: 'css'` keeps Emotion's default class
 * prefix, so nothing that targets the generated class names changes.
 */
export function createEmotionCache(doc: Document = document): EmotionCache {
  return createCache({ key: 'css', nonce: readCspNonce(doc) });
}
