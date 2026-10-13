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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  MailTemplateListResponse,
  MailTemplateResponse,
  SendTestEmailResponse,
  UpdateMailTemplateRequest,
} from '../generated';
import { adminEmailApi } from '../config';

export const mailTemplateKeys = {
  all: ['admin', 'mail-templates'] as const,
  /** Every language of one template; the prefix of {@link mailTemplateKeys.localized}. */
  detail: (key: string) => ['admin', 'mail-templates', key] as const,
  /** One template in one language; `undefined` is the configured default language. */
  localized: (key: string, locale: string | undefined) =>
    ['admin', 'mail-templates', key, locale ?? 'default'] as const,
};

/** All mail templates with their current (override or built-in) content. */
export function useMailTemplates() {
  return useQuery<MailTemplateListResponse>({
    queryKey: mailTemplateKeys.all,
    queryFn: async () => {
      const response = await adminEmailApi.listMailTemplates();
      return response.data;
    },
  });
}

/** One template's effective content + editor metadata (issue #144), for the edit page. */
export function useMailTemplate(key: string, locale?: string) {
  return useQuery<MailTemplateResponse>({
    queryKey: mailTemplateKeys.localized(key, locale),
    queryFn: async () => {
      const response = await adminEmailApi.getMailTemplate({ key, locale });
      return response.data;
    },
    enabled: key.length > 0,
    refetchOnWindowFocus: false,
  });
}

/** Creates or updates a per-locale template override, then refreshes the list. */
export function useUpdateMailTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (vars: { key: string; request: UpdateMailTemplateRequest }) => {
      const response = await adminEmailApi.updateMailTemplate({
        key: vars.key,
        updateMailTemplateRequest: vars.request,
      });
      return response.data;
    },
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: mailTemplateKeys.all });
      queryClient.invalidateQueries({ queryKey: mailTemplateKeys.detail(vars.key) });
    },
  });
}

/** Removes one language's override, reverting it to that language's default, then refreshes. */
export function useResetMailTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ key, locale }: { key: string; locale: string }) => {
      await adminEmailApi.resetMailTemplate({ key, locale });
    },
    onSuccess: (_data, { key }) => {
      queryClient.invalidateQueries({ queryKey: mailTemplateKeys.all });
      queryClient.invalidateQueries({ queryKey: mailTemplateKeys.detail(key) });
    },
  });
}

/**
 * Sends a test email with the current SMTP settings. The endpoint never fails
 * the request — the outcome (SENT/SKIPPED/FAILED) is in the response body.
 */
export function useSendTestEmail() {
  return useMutation({
    mutationFn: async (recipient: string): Promise<SendTestEmailResponse> => {
      const response = await adminEmailApi.sendTestEmail({ sendTestEmailRequest: { recipient } });
      return response.data;
    },
  });
}
