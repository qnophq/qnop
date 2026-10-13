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

  it.each(namespaces)('%s keeps every placeholder and markup tag', (namespace) => {
    // A translation that drops {{name}} or a <Trans> tag passes the key check but renders
    // a sentence with a hole in it (or loses its link).
    const english = flatten(catalog('en', namespace));
    const translated = flatten(catalog(locale, namespace));
    // Every translated value is checked, including plural forms English lacks (Spanish and French
    // `_many`): against the English value of the same key, or — for a plural form — the English
    // `_other`. That second option also lets a plural form show `{{count}}` where English writes
    // "One more …" without it: i18next always passes `count`, and French "one" covers 0.
    const accepted = (key: string): string[] => {
      const other = english[key.replace(/_(zero|one|two|few|many)$/, '_other')];
      return [english[key], other].filter((text) => text !== undefined).map(tokensOf);
    };
    const mismatches = Object.entries(translated)
      .filter(([key, text]) => !accepted(key).includes(tokensOf(text)))
      .map(([key]) => key);
    expect(mismatches).toEqual([]);
  });

  it.each(namespaces)('%s covers every plural category of the language', (namespace) => {
    // i18next picks the form by Intl.PluralRules; a category the catalog lacks (Spanish "many",
    // e.g. 1 000 000) does not fall back to "other" but to the English catalog.
    const categories = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;
    const keys = Object.keys(flatten(catalog(locale, namespace)));
    const plural = /_(zero|one|two|few|many|other)$/;
    const bases = new Set(
      keys.filter((key) => plural.test(key)).map((key) => key.replace(plural, '')),
    );
    const missing = [...bases].flatMap((base) =>
      categories
        .filter((category) => !keys.includes(`${base}_${category}`))
        .map((c) => `${base}_${c}`),
    );
    expect(missing).toEqual([]);
  });

  it.each(namespaces)('%s has no empty strings', (namespace) => {
    const empty = Object.entries(flatten(catalog(locale, namespace))).filter(([, v]) => !v.trim());
    expect(empty).toEqual([]);
  });
});

/** The interpolations and Trans tags a string uses, as a sorted signature. */
function tokensOf(text: string): string {
  const tokens = text.match(/\{\{[^}]+\}\}|<\/?[A-Za-z][\w-]*\s*\/?>/g) ?? [];
  return tokens
    .map((token) => token.replace(/\s+/g, ''))
    .sort()
    .join(' ');
}

/**
 * French typography (issue #851): a narrow no-break space before ; ? ! and a no-break space before
 * a colon, guillemets with no-break spaces inside. A plain or missing space renders a line break or
 * a cramped sign that French readers notice at once.
 */
describe.each(SUPPORTED_LOCALES.filter((locale) => locale === 'fr'))('%s typography', (locale) => {
  it.each(namespaces)('%s spaces its punctuation the French way', (namespace) => {
    const offending = Object.entries(flatten(catalog(locale, namespace))).filter(([, text]) => {
      // Placeholders and tags are code, not prose; URLs and clock times keep their colons.
      const prose = text
        .replace(/\{\{[^}]+\}\}|<[^>]+>/g, '')
        .replace(/https?:\/\/\S+/g, '')
        .replace(/\d{1,2}:\d{2}/g, '');
      return (
        /[^\u202F\s][;?!]|[ \u00A0][;?!]/.test(prose) ||
        /[^\u00A0\s]:|[ \u202F]:/.test(prose) ||
        /«(?!\u00A0)|(?<!\u00A0)»/.test(prose) ||
        /[A-Za-zÀ-ÿ]'[A-Za-zÀ-ÿ]/.test(prose)
      );
    });
    expect(offending.map(([key]) => key)).toEqual([]);
  });
});

describe('catalog files', () => {
  it('exist only for supported languages and English namespaces', () => {
    const stray = Object.keys(catalogs).filter((path) => {
      const [, locale, file] = path.split('/');
      const namespace = file.slice(0, -'.json'.length);
      return (
        !(SUPPORTED_LOCALES as readonly string[]).includes(locale) ||
        !namespaces.includes(namespace)
      );
    });
    expect(stray).toEqual([]);
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
