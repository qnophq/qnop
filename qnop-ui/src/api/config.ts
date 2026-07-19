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

import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import {
  AdminConfigurationApi,
  AdminEmailApi,
  AdminOidcProvidersApi,
  AdminSchedulerApi,
  AdminSettingsApi,
  AdminTeamsApi,
  AdminUsersApi,
  AnnotationsApi,
  AuditApi,
  AuthApi,
  AuthPasswordResetApi,
  AuthRegistrationApi,
  DocumentsApi,
  PrincipalsApi,
  ReviewWorkflowApi,
  ServerConfigApi,
  TeamsApi,
  UserSettingsApi,
  UsersApi,
  DashboardApi,
} from './generated';
import { performRefresh } from './refresh';
import { useAuthStore } from '../stores/authStore';

const API_BASE = '/api/v1';

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

/**
 * The shared axios instance for all API calls. `baseURL` is set so the
 * generated typescript-axios clients use it directly (they skip their own
 * BASE_PATH when `defaults.baseURL` is present). `withCredentials` sends the
 * HttpOnly refresh cookie. The request interceptor attaches the in-memory
 * bearer token; the response interceptor performs exactly one refresh + retry
 * on a 401.
 */
export const axiosInstance = axios.create({
  baseURL: API_BASE,
  withCredentials: true,
});

axiosInstance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

axiosInstance.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined;
    const status = error.response?.status;
    if (status !== 401 || !original || original._retry) {
      return Promise.reject(error);
    }
    original._retry = true;
    const token = await performRefresh();
    if (!token) {
      useAuthStore.getState().clearAuth();
      return Promise.reject(error);
    }
    useAuthStore.getState().setAccessToken(token);
    original.headers.set('Authorization', `Bearer ${token}`);
    return axiosInstance.request(original);
  },
);

// Generated, type-safe API clients bound to the shared axios instance. Add more
// here as endpoints are consumed (admin users, teams, settings — #104+).
export const serverConfigApi = new ServerConfigApi(undefined, undefined, axiosInstance);
export const usersApi = new UsersApi(undefined, undefined, axiosInstance);
export const authApi = new AuthApi(undefined, undefined, axiosInstance);
export const authRegistrationApi = new AuthRegistrationApi(undefined, undefined, axiosInstance);
export const authPasswordResetApi = new AuthPasswordResetApi(undefined, undefined, axiosInstance);
export const adminUsersApi = new AdminUsersApi(undefined, undefined, axiosInstance);
export const adminTeamsApi = new AdminTeamsApi(undefined, undefined, axiosInstance);
export const teamsApi = new TeamsApi(undefined, undefined, axiosInstance);
export const adminSettingsApi = new AdminSettingsApi(undefined, undefined, axiosInstance);
export const adminConfigurationApi = new AdminConfigurationApi(undefined, undefined, axiosInstance);
export const adminSchedulerApi = new AdminSchedulerApi(undefined, undefined, axiosInstance);
export const adminOidcProvidersApi = new AdminOidcProvidersApi(undefined, undefined, axiosInstance);
export const adminEmailApi = new AdminEmailApi(undefined, undefined, axiosInstance);
export const documentsApi = new DocumentsApi(undefined, undefined, axiosInstance);
export const dashboardApi = new DashboardApi(undefined, undefined, axiosInstance);
export const auditApi = new AuditApi(undefined, undefined, axiosInstance);
export const annotationsApi = new AnnotationsApi(undefined, undefined, axiosInstance);
export const principalsApi = new PrincipalsApi(undefined, undefined, axiosInstance);
export const reviewWorkflowApi = new ReviewWorkflowApi(undefined, undefined, axiosInstance);
export const userSettingsApi = new UserSettingsApi(undefined, undefined, axiosInstance);
