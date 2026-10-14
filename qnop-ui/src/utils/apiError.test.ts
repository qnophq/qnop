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

import { AxiosError, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import { applyLocale } from '../i18n';
import type { AppLocale } from '../i18n/locales';
import { apiErrorCode, apiErrorMessage, apiFieldErrors } from './apiError';

function axiosWithStatus(status: number): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, { status } as AxiosResponse);
}

function axiosWithBody(status: number, data: unknown): AxiosError {
  return new AxiosError('x', 'ERR', undefined, undefined, { status, data } as AxiosResponse);
}

describe('apiErrorMessage', () => {
  it('maps a 429 to a rate-limit message', () => {
    expect(apiErrorMessage(axiosWithStatus(429), 'fallback')).toMatch(/attempts/i);
  });

  it('maps a 400 without a validation code to an invalid/expired message', () => {
    expect(apiErrorMessage(axiosWithStatus(400), 'fallback')).toMatch(/invalid|expired/i);
  });

  it('maps a 400 VALIDATION_ERROR to the caller fallback, not the link-expired text', () => {
    const error = axiosWithBody(400, { code: 'VALIDATION_ERROR' });
    expect(apiErrorMessage(error, 'fallback')).toBe('fallback');
  });

  it('maps the RATE_LIMITED sentinel error to the rate-limit message', () => {
    expect(apiErrorMessage(new Error('RATE_LIMITED'), 'fallback')).toMatch(/attempts/i);
  });

  it('falls back for unknown errors', () => {
    expect(apiErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    expect(apiErrorMessage(axiosWithStatus(500), 'fallback')).toBe('fallback');
  });

  it('resolves its own texts in the active language at call time', async () => {
    await applyLocale('de');
    try {
      expect(apiErrorMessage(axiosWithStatus(429), 'fallback')).toBe(
        'Zu viele Versuche. Bitte versuche es später noch einmal.',
      );
    } finally {
      await applyLocale('en');
    }
    expect(apiErrorMessage(axiosWithStatus(429), 'fallback')).toBe(
      'Too many attempts. Please try again later.',
    );
  });
});

describe('apiErrorCode', () => {
  it('extracts the code from the error envelope', () => {
    expect(apiErrorCode(axiosWithBody(409, { code: 'EMAIL_TAKEN' }))).toBe('EMAIL_TAKEN');
  });

  it('returns undefined when there is no code or it is not an axios error', () => {
    expect(apiErrorCode(axiosWithBody(409, { message: 'x' }))).toBeUndefined();
    expect(apiErrorCode(axiosWithStatus(500))).toBeUndefined();
    expect(apiErrorCode(new Error('boom'))).toBeUndefined();
  });
});

describe('apiFieldErrors', () => {
  it('maps the fieldErrors array to a field→message record', () => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      message: '2 settings are invalid.',
      fieldErrors: [
        { field: 'smtp.port', message: 'must be at most 65535' },
        { field: 'smtp.from', message: 'must be a valid email address' },
      ],
    });
    expect(apiFieldErrors(error)).toEqual({
      'smtp.port': 'must be at most 65535',
      'smtp.from': 'must be a valid email address',
    });
  });

  it('words a known reason code in the active language, with its params', async () => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      message: '2 settings are invalid.',
      fieldErrors: [
        {
          field: 'smtp.port',
          message: 'must be at most 65535',
          code: 'TOO_LARGE',
          params: { max: '65535' },
        },
        {
          field: 'username',
          message: 'size must be between 3 and 64',
          code: 'LENGTH_OUT_OF_RANGE',
          params: { min: '3', max: '64' },
        },
      ],
    });
    expect(apiFieldErrors(error)).toEqual({
      'smtp.port': 'must be at most 65535',
      username: 'must be between 3 and 64 characters',
    });

    await applyLocale('de');
    try {
      expect(apiFieldErrors(error)).toEqual({
        'smtp.port': 'darf höchstens 65535 sein',
        username: 'muss zwischen 3 und 64 Zeichen lang sein',
      });
    } finally {
      await applyLocale('en');
    }
  });

  it('words a single length bound in the singular when it is 1', async () => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        {
          field: 'name',
          message: 'size must be at least 1',
          code: 'TOO_SHORT',
          params: { min: '1' },
        },
        {
          field: 'initial',
          message: 'size must be at most 1',
          code: 'TOO_LONG',
          params: { max: '1' },
        },
        {
          field: 'title',
          message: 'size must be at most 200',
          code: 'TOO_LONG',
          params: { max: '200' },
        },
      ],
    });
    expect(apiFieldErrors(error)).toEqual({
      name: 'must be at least 1 character',
      initial: 'must be at most 1 character',
      title: 'must be at most 200 characters',
    });

    await applyLocale('fr');
    try {
      expect(apiFieldErrors(error)).toEqual({
        name: 'doit comporter au moins 1 caractère',
        initial: 'doit comporter au maximum 1 caractère',
        title: 'doit comporter au maximum 200 caractères',
      });
    } finally {
      await applyLocale('en');
    }
  });

  it.each<[AppLocale, string, string]>([
    ['es', 'debe tener al menos 1 carácter', 'debe tener al menos 0 caracteres'],
    ['pt', 'deve ter pelo menos 1 caractere', 'deve ter pelo menos 0 caractere'],
    ['fr', 'doit comporter au moins 1 caractère', 'doit comporter au moins 0 caractère'],
    ['en', 'must be at least 1 character', 'must be at least 0 characters'],
  ])('%s picks the plural category of the bound, 0 included', async (locale, one, zero) => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        { field: 'a', message: 'size must be at least 1', code: 'TOO_SHORT', params: { min: '1' } },
        { field: 'b', message: 'size must be at least 0', code: 'TOO_SHORT', params: { min: '0' } },
      ],
    });
    await applyLocale(locale);
    try {
      expect(apiFieldErrors(error)).toEqual({ a: one, b: zero });
    } finally {
      await applyLocale('en');
    }
  });

  it('keeps the server message when a counted bound is missing or not a number', () => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        { field: 'a', message: 'size must be at least x', code: 'TOO_SHORT', params: { min: 'x' } },
        { field: 'b', message: 'size must be at most ?', code: 'TOO_LONG' },
      ],
    });
    expect(apiFieldErrors(error)).toEqual({
      a: 'size must be at least x',
      b: 'size must be at most ?',
    });
  });

  it('keeps the server message for an unknown or missing code', async () => {
    const error = axiosWithBody(400, {
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        { field: 'password', message: 'must contain a digit' },
        { field: 'slug', message: 'is reserved', code: 'SOMETHING_NEW' },
      ],
    });
    await applyLocale('de');
    try {
      expect(apiFieldErrors(error)).toEqual({
        password: 'must contain a digit',
        slug: 'is reserved',
      });
    } finally {
      await applyLocale('en');
    }
  });

  it('returns an empty object when there are no field errors', () => {
    expect(apiFieldErrors(axiosWithBody(400, { code: 'VALIDATION_ERROR' }))).toEqual({});
    expect(apiFieldErrors(new Error('boom'))).toEqual({});
  });
});
