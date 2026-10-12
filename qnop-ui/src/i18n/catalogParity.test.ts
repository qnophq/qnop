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

type Catalog = { [key: string]: string | Catalog };

const catalogs = import.meta.glob<Catalog>('./*/*.json', { eager: true, import: 'default' });

/** Flattens a catalog to its dotted keys, folding plural suffixes onto their base key. */
function keysOf(catalog: Catalog, prefix = ''): string[] {
  return Object.entries(catalog).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return typeof value === 'string'
      ? [path.replace(/_(zero|one|two|few|many|other)$/, '')]
      : keysOf(value, path);
  });
}

function catalog(locale: string, namespace: string): Catalog {
  const found = catalogs[`./${locale}/${namespace}.json`];
  if (!found) throw new Error(`Missing catalog ${locale}/${namespace}.json`);
  return found;
}

const namespaces = Object.keys(catalogs)
  .filter((path) => path.startsWith('./en/'))
  .map((path) => path.slice('./en/'.length, -'.json'.length));

/**
 * Every language mirrors the English catalog (issue #464, ADR-0063): a key missing in a
 * translation would silently render English, and an extra key is dead text nobody reviews.
 */
describe.each(SUPPORTED_LOCALES.filter((locale) => locale !== 'en'))('%s catalogs', (locale) => {
  it.each(namespaces)('%s has exactly the English keys', (namespace) => {
    const english = [...new Set(keysOf(catalog('en', namespace)))].sort();
    const translated = [...new Set(keysOf(catalog(locale, namespace)))].sort();
    expect(translated).toEqual(english);
  });

  it.each(namespaces)('%s has no empty strings', (namespace) => {
    const empty = Object.entries(flatten(catalog(locale, namespace))).filter(([, v]) => !v.trim());
    expect(empty).toEqual([]);
  });
});

function flatten(catalog: Catalog, prefix = ''): Record<string, string> {
  return Object.fromEntries(
    Object.entries(catalog).flatMap(([key, value]) => {
      const path = prefix ? `${prefix}.${key}` : key;
      return typeof value === 'string' ? [[path, value]] : Object.entries(flatten(value, path));
    }),
  );
}
